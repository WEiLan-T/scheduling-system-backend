package com.company.scheduling.service.scheduling;

import com.company.scheduling.domain.*;
import com.company.scheduling.dto.MultiOrderScheduleRequest;
import com.company.scheduling.dto.ScheduleAdjustmentRequest;
import com.company.scheduling.repository.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 核心排产引擎：单订单与多订单排产算法
 */
@Service
public class SchedulingEngine {

    private final CapacityMatcher capacityMatcher;
    private final ResourceAllocator resourceAllocator;
    private final ProductionOrderRepo orderRepo;
    private final ProductProcessRepo processRepo;
    private final WeavingMachineStatusRepo weavingStatusRepo;
    private final CoexLineStatusRepo coexStatusRepo;
    private final EstimatedProductionScheduleRepo scheduleRepo;
    private final CapacityProvider capacityProvider;
    private final TapeStockConsumer stockConsumer;

    public SchedulingEngine(CapacityMatcher capacityMatcher,
                            ResourceAllocator resourceAllocator,
                            ProductionOrderRepo orderRepo,
                            ProductProcessRepo processRepo,
                            WeavingMachineStatusRepo weavingStatusRepo,
                            CoexLineStatusRepo coexStatusRepo,
                            EstimatedProductionScheduleRepo scheduleRepo,
                            CapacityProvider capacityProvider,
                            TapeStockConsumer stockConsumer) {
        this.capacityMatcher = capacityMatcher;
        this.resourceAllocator = resourceAllocator;
        this.orderRepo = orderRepo;
        this.processRepo = processRepo;
        this.weavingStatusRepo = weavingStatusRepo;
        this.coexStatusRepo = coexStatusRepo;
        this.scheduleRepo = scheduleRepo;
        this.capacityProvider = capacityProvider;
        this.stockConsumer = stockConsumer;
    }

    // ================ 单订单排产 ================
    public Map<String, Object> previewSingleOrder(ScheduleAdjustmentRequest req, String currentUser) {
        List<ProductionOrder> orders = (req.getDraftOrders() != null && !req.getDraftOrders().isEmpty())
                ? req.getDraftOrders() : orderRepo.findByOrderId(req.getOrderId());
        if (orders == null || orders.isEmpty()) throw new RuntimeException("查无此订单，请核对订单号！");

        // 产能快照：一次 findAll 构建工艺库标准产能 O(1) 查找 Map
        CapacityProvider.CapacitySnapshot capSnapshot = capacityProvider.loadSnapshot();

        int bufferDays = req.getGlobalBufferDays() != null ? req.getGlobalBufferDays() : 3;
        int weaveAdvance = req.getWeavingAdvanceDays() != null ? req.getWeavingAdvanceDays() : 2;

        LocalDateTime overallStartDate = LocalDateTime.MAX;
        LocalDateTime overallEndDate = LocalDateTime.MIN;
        List<Map<String, Object>> itemSchedules = new ArrayList<>();
        // 需求6：带坯供给模拟冲突告警收集容器（与 previewMultiOrder 的 ResourceTimeline.conflictWarnings 同机制）
        List<String> warnings = new ArrayList<>();

        List<WeavingMachineStatus> allWMachines = weavingStatusRepo.findAll();
        List<CoexLineStatus> allCLines = coexStatusRepo.findAll();

        for (ProductionOrder item : orders) {
            BigDecimal finishedMeters = calcFinishedMeters(item);
            if (finishedMeters == null || finishedMeters.compareTo(BigDecimal.ZERO) <= 0) {
                throw new RuntimeException("订单明细 [" + item.getFinishedPartNumber() + "] 缺少数量信息（单卷长度/卷数/总数量），请先完善订单数据！");
            }
            BigDecimal tapeMetersNeeded = finishedMeters;

            ProductProcess proc = processRepo.findByFinishedPartNumber(item.getFinishedPartNumber())
                    .orElseThrow(() -> new RuntimeException("MISSING_PROCESS:" + item.getFinishedPartNumber()));
            String tapePartNumber = proc.getTapePartNumber();

            // 库存整根贪心消耗：以带坯零件号查库存（修复原成品零件号查键Bug），仅计已落库整根，快照日期FIFO；
            // 已消耗整根直接进入共挤生产，仅缺口部分进入织造缺口计算
            TapeStockConsumer.ConsumptionResult stock = stockConsumer.consume(tapePartNumber, proc.getTapeModelSpec(), tapeMetersNeeded);
            BigDecimal shortfall = stock.getShortfall();

            ScheduleAdjustmentRequest.ItemAdjustment itemAdj = req.getItemAdjustments() != null ?
                    req.getItemAdjustments().stream().filter(a -> a.getFinishedPartNumber().equals(item.getFinishedPartNumber())).findFirst().orElse(null) : null;

            // 产能解析：人工覆盖 > 工艺库标准值 > MISSING_CAPACITY 熔断
            BigDecimal wCap = capSnapshot.resolveWeavingCapacity(tapePartNumber, item.getFinishedPartNumber(),
                    itemAdj != null ? itemAdj.getManualWeavingCapacity() : null);
            BigDecimal cCap = capSnapshot.resolveCoexCapacity(item.getFinishedPartNumber(), tapePartNumber,
                    itemAdj != null ? itemAdj.getManualCoexCapacity() : null);
            // 织造储备库存：共挤开工前需提前储备 cCap×reserveDays 的带坯米数，
            // 仅作为织造开工时点偏移量（extraAdvanceDays 天），严禁计入 tapeMetersNeed 缺口米数；
            // reserveDays 缺省 0 时 reserveMeters=0、extraAdvanceDays=0，与现状排产结果完全一致
            BigDecimal[] reserve = calcWeavingReserve(req.getWeavingReserveDays(), wCap, cCap);
            BigDecimal reserveMeters = reserve[0];
            int reserveAdvanceDays = reserve[1].intValue();
            BigDecimal changeoverDays = new BigDecimal("1");
            Integer delayDays = 1;

            LocalDateTime rawDeadline = item.getDeliveryDate() != null ? item.getDeliveryDate().atTime(23, 59, 59) : LocalDate.now().plusDays(30).atTime(23, 59, 59);
            LocalDateTime deadline = rawDeadline.minusDays(bufferDays);

            LocalDateTime now = LocalDateTime.now();
            long availableHours = ChronoUnit.HOURS.between(now, deadline);
            if (availableHours <= 0) availableHours = 1;

            double wHoursNeeded = shortfall.compareTo(BigDecimal.ZERO) > 0 ? shortfall.divide(wCap, 4, RoundingMode.HALF_UP).doubleValue() * 24.0 : 0;
            double cHoursNeeded = finishedMeters.divide(cCap, 4, RoundingMode.HALF_UP).doubleValue() * 24.0;

            // 口径匹配：传完整规格字符串走区间判定（spec 区间落入 limit 区间）；
            // 候选为空时告警写入 warnings 容器，随预览响应的 conflictWarnings 键透传至前端
            String caliberSpec = proc.getFinishedModelSpec();
            List<WeavingMachineStatus> candidateWMachines = capacityMatcher.findBestWeavingMachinesBySpec(caliberSpec, allWMachines, warnings);
            List<CoexLineStatus> candidateCLines = capacityMatcher.findBestCoexLinesBySpec(caliberSpec, allCLines, warnings);

            // 计算织造需要的机台数
            int machineCount;
            if (itemAdj != null && itemAdj.getManualWeavingMachineCount() != null && itemAdj.getManualWeavingMachineCount() > 0) {
                machineCount = itemAdj.getManualWeavingMachineCount();
            } else {
                machineCount = wHoursNeeded > availableHours ? (int) Math.ceil(wHoursNeeded / availableHours) : 1;
            }

            // 计算共挤需要的产线数
            int lineCount;
            if (itemAdj != null && itemAdj.getManualCoexLineCount() != null && itemAdj.getManualCoexLineCount() > 0) {
                lineCount = itemAdj.getManualCoexLineCount();
            } else {
                lineCount = cHoursNeeded > availableHours ? (int) Math.ceil(cHoursNeeded / availableHours) : 1;
            }

            // 物理上限约束
            int maxMachines = candidateWMachines.size();
            int maxLines = candidateCLines.size();
            if (machineCount > maxMachines) machineCount = Math.max(1, maxMachines);
            if (lineCount > maxLines) lineCount = Math.max(1, maxLines);

            // splitShortfall 仅作用于新织造缺口部分（库存整根已消耗，不再均分到织造）；splitFinished 为共挤总米数
            BigDecimal splitShortfall = machineCount > 0 ? shortfall.divide(new BigDecimal(machineCount), 4, RoundingMode.HALF_UP) : shortfall;
            BigDecimal splitFinished = lineCount > 0 ? finishedMeters.divide(new BigDecimal(lineCount), 4, RoundingMode.HALF_UP) : finishedMeters;

            // 已消耗整根按长度序列分配到共挤产线
            TapeStockConsumer.RollDistribution rollDistribution = TapeStockConsumer.distributeByLine(stock.getRolls(), lineCount, splitFinished);

            // 支持指定机台/产线列表
            List<WeavingMachineStatus> selectedWMachines = new ArrayList<>();
            List<CoexLineStatus> selectedCLines = new ArrayList<>();

            if (itemAdj != null && itemAdj.getAssignedMachineIds() != null && !itemAdj.getAssignedMachineIds().isEmpty()) {
                for (String mid : itemAdj.getAssignedMachineIds()) {
                    candidateWMachines.stream().filter(m -> m.getMachineId().equals(mid)).findFirst().ifPresent(selectedWMachines::add);
                }
            }
            if (itemAdj != null && itemAdj.getAssignedLineIds() != null && !itemAdj.getAssignedLineIds().isEmpty()) {
                for (String lid : itemAdj.getAssignedLineIds()) {
                    candidateCLines.stream().filter(l -> l.getLineId().equals(lid)).findFirst().ifPresent(selectedCLines::add);
                }
            }

            Set<String> usedW = new HashSet<>();
            Set<String> usedC = new HashSet<>();
            // 需求6：按分配顺序收集织造机台ID，供共挤换带坯模拟使用
            List<String> weavingMachineIds = new ArrayList<>();

            // 织造独立分配
            for (int i = 0; i < machineCount; i++) {
                WeavingMachineStatus wm;
                if (i < selectedWMachines.size()) {
                    wm = selectedWMachines.get(i);
                } else {
                    wm = candidateWMachines.stream()
                            .filter(m -> !usedW.contains(m.getMachineId()))
                            .max(Comparator.comparingInt(m -> capacityMatcher.scoreWeavingMachine(m, proc.getWarpSpec(), allWMachines)))
                            .orElse(null);
                }
                if (wm != null) {
                    usedW.add(wm.getMachineId());
                    weavingMachineIds.add(wm.getMachineId());
                }

                BigDecimal splitWHours = splitShortfall.divide(wCap, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("24"));
                LocalDateTime weavingEnd = deadline.minusDays(weaveAdvance);
                // 储备偏移：织造起点相对共挤再提前 reserveAdvanceDays 天（缺省 0 天时无偏移）；
                // 与 weaveAdvance（织造提前结束天数）各自独立叠加，互不干扰
                LocalDateTime weavingStart = weavingEnd.minusMinutes(splitWHours.multiply(new BigDecimal("60")).longValue()).minusDays(reserveAdvanceDays);

                if (weavingStart.isBefore(now)) {
                    long shiftMinutes = ChronoUnit.MINUTES.between(weavingStart, now);
                    weavingStart = weavingStart.plusMinutes(shiftMinutes);
                    weavingEnd = weavingEnd.plusMinutes(shiftMinutes);
                }

                ScheduleDates wDates = new ScheduleDates();
                wDates.startDate = shortfall.compareTo(BigDecimal.ZERO) > 0 ? weavingStart : null;
                wDates.endDate = shortfall.compareTo(BigDecimal.ZERO) > 0 ? weavingEnd : null;
                wDates.algoWeavingCapacity = wCap;
                wDates.algoChangeoverDays = changeoverDays;
                ScheduleDates cDates = new ScheduleDates();
                cDates.algoCoexCapacity = cCap;
                cDates.algoDelayDays = delayDays;

                Map<String, Object> draftItem = buildDraftView(item.getFinishedPartNumber(), tapePartNumber, proc.getWarpSpec(), proc.getWeftSpec(), proc.getFinishedModelSpec(), proc.getTapeModelSpec(), splitFinished, splitShortfall, wDates, cDates, req.getOrderId(), Collections.emptyList(), null, reserveMeters, reserveAdvanceDays);
                draftItem.put("plannedMachine", wm != null ? wm.getMachineId() : null);
                draftItem.put("plannedLine", null);
                draftItem.put("allocationType", "weaving");
                itemSchedules.add(draftItem);

                if (wDates.startDate != null && wDates.startDate.isBefore(overallStartDate)) overallStartDate = wDates.startDate;
            }

            // 共挤独立分配
            for (int i = 0; i < lineCount; i++) {
                CoexLineStatus cl;
                if (i < selectedCLines.size()) {
                    cl = selectedCLines.get(i);
                } else {
                    cl = candidateCLines.stream().filter(l -> !usedC.contains(l.getLineId())).findFirst().orElse(null);
                }
                if (cl != null) usedC.add(cl.getLineId());

                BigDecimal splitCHours = splitFinished.divide(cCap, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("24"));
                LocalDateTime coexEnd = deadline;
                LocalDateTime coexStart = coexEnd.minusMinutes(splitCHours.multiply(new BigDecimal("60")).longValue());

                if (coexStart.isBefore(now)) {
                    long shiftMinutes = ChronoUnit.MINUTES.between(coexStart, now);
                    coexStart = coexStart.plusMinutes(shiftMinutes);
                    coexEnd = coexEnd.plusMinutes(shiftMinutes);
                }

                ScheduleDates wDates = new ScheduleDates();
                wDates.algoWeavingCapacity = wCap;
                wDates.algoChangeoverDays = changeoverDays;
                ScheduleDates cDates = new ScheduleDates();
                cDates.startDate = coexStart;
                cDates.endDate = coexEnd;
                cDates.algoCoexCapacity = cCap;
                cDates.algoDelayDays = delayDays;

                Map<String, Object> draftItem = buildDraftView(item.getFinishedPartNumber(), tapePartNumber, proc.getWarpSpec(), proc.getWeftSpec(), proc.getFinishedModelSpec(), proc.getTapeModelSpec(), splitFinished, splitShortfall, wDates, cDates, req.getOrderId(),
                        rollDistribution.byLine.get(i), i == rollDistribution.lastRollLineIndex ? stock.getSurplusMeters() : null, reserveMeters, reserveAdvanceDays);
                draftItem.put("plannedMachine", null);
                draftItem.put("plannedLine", cl != null ? cl.getLineId() : null);
                draftItem.put("allocationType", "coex");
                // 需求6：模拟共挤中途换带坯时点（仅挂载 draftItem 展示，不落库）：
                // 供给段 = 该产线分配到的库存整根 + 按比例映射到本产线的织造机台（每台可供米数=splitShortfall）
                draftItem.put("tapeChangeEvents", TapeSupplySimulator.simulateTapeChanges(
                        cl != null ? cl.getLineId() : null, coexStart, cCap, wCap, reserveMeters,
                        buildLineSupplySegments(rollDistribution.byLine.get(i), weavingMachineIds, lineCount, i, splitShortfall),
                        warnings));
                itemSchedules.add(draftItem);

                if (cDates.startDate.isBefore(overallStartDate)) overallStartDate = cDates.startDate;
                if (cDates.endDate.isAfter(overallEndDate)) overallEndDate = cDates.endDate;
            }
        }

        Map<String, Object> draft = new HashMap<>();
        draft.put("orderId", req.getOrderId()); draft.put("overallStartDate", overallStartDate.toString()); draft.put("overallEndDate", overallEndDate.toString()); draft.put("totalDays", ChronoUnit.DAYS.between(overallStartDate.toLocalDate(), overallEndDate.toLocalDate()) + 1); draft.put("details", itemSchedules);
        draft.put("conflictWarnings", warnings);
        return draft;
    }

    // ================ 多订单并发排产引擎 ================
    public Map<String, Object> previewMultiOrder(MultiOrderScheduleRequest req, String currentUser) {
        List<String> orderIds = req.getOrderIds();
        if (orderIds == null || orderIds.isEmpty()) throw new RuntimeException("订单列表不能为空！");

        int bufferDays = req.getGlobalBufferDays() != null ? req.getGlobalBufferDays() : 3;
        int weaveAdvance = req.getWeavingAdvanceDays() != null ? req.getWeavingAdvanceDays() : 2;

        // 1. 🌟 性能优化：批量查询所有订单 + 一次性加载产能缓存
        List<ProductionOrder> allOrdersBatch = orderRepo.findByOrderIdIn(orderIds);
        Map<String, List<ProductionOrder>> ordersByOrderId = allOrdersBatch.stream()
                .collect(Collectors.groupingBy(ProductionOrder::getOrderId));
        List<ProductionOrder> allOrders = new ArrayList<>(allOrdersBatch);
        if (allOrders.isEmpty()) throw new RuntimeException("查无订单，请核对订单号！");
        allOrders.sort(Comparator.comparing(o -> o.getDeliveryDate() != null ? o.getDeliveryDate() : LocalDate.now().plusYears(10)));

        // 产能快照：一次 findAll 构建工艺库标准产能 O(1) 查找 Map
        CapacityProvider.CapacitySnapshot capSnapshot = capacityProvider.loadSnapshot();

        // 🌟 预构建 finishedPartNumber → orderId 的 O(1) 查找 Map
        Map<String, String> partNumberToOrderId = new HashMap<>();
        for (Map.Entry<String, List<ProductionOrder>> entry : ordersByOrderId.entrySet()) {
            for (ProductionOrder po : entry.getValue()) {
                if (po.getFinishedPartNumber() != null) {
                    partNumberToOrderId.put(po.getFinishedPartNumber(), entry.getKey());
                }
            }
        }

        // 2. 🌟 性能优化：批量查询未来排产记录初始化资源时间线
        LocalDateTime now = LocalDateTime.now();

        List<WeavingMachineStatus> allWMachines = weavingStatusRepo.findAll();
        List<CoexLineStatus> allCLines = coexStatusRepo.findAll();

        // 批量查询所有未来排产记录，替代逐机台/产线查询
        List<EstimatedProductionSchedule> allFutureWeaving = scheduleRepo.findByWeavingEndDateAfter(now);
        List<EstimatedProductionSchedule> allFutureCoex = scheduleRepo.findByCoexEndDateAfter(now);

        ResourceAllocator.ResourceTimeline timeline = resourceAllocator.createTimeline();
        allFutureWeaving.stream()
                .filter(e -> e.getWeavingMachineId() != null && e.getWeavingEndDate() != null)
                .forEach(e -> {
                    LocalDateTime current = timeline.getMachineAvailableTime(e.getWeavingMachineId(), null);
                    if (current == null || e.getWeavingEndDate().isAfter(current)) {
                        timeline.updateMachineTimeline(e.getWeavingMachineId(), e.getWeavingEndDate());
                    }
                });
        allFutureCoex.stream()
                .filter(e -> e.getCoexLineId() != null && e.getCoexEndDate() != null)
                .forEach(e -> {
                    LocalDateTime current = timeline.getLineAvailableTime(e.getCoexLineId(), null);
                    if (current == null || e.getCoexEndDate().isAfter(current)) {
                        timeline.updateLineTimeline(e.getCoexLineId(), e.getCoexEndDate());
                    }
                });

        // 3. 逐订单排产
        LocalDateTime overallStartDate = LocalDateTime.MAX;
        LocalDateTime overallEndDate = LocalDateTime.MIN;
        List<Map<String, Object>> allResults = new ArrayList<>();

        for (ProductionOrder item : allOrders) {
            BigDecimal finishedMeters = calcFinishedMeters(item);
            if (finishedMeters == null || finishedMeters.compareTo(BigDecimal.ZERO) <= 0) {
                throw new RuntimeException("订单明细 [" + item.getFinishedPartNumber() + "] 缺少数量信息（单卷长度/卷数/总数量），请先完善订单数据！");
            }
            BigDecimal tapeMetersNeeded = finishedMeters;

            ProductProcess proc = processRepo.findByFinishedPartNumber(item.getFinishedPartNumber())
                    .orElseThrow(() -> new RuntimeException("MISSING_PROCESS:" + item.getFinishedPartNumber()));
            String tapePartNumber = proc.getTapePartNumber();

            // 库存整根贪心消耗：以带坯零件号查库存（修复原成品零件号查键Bug），仅计已落库整根，快照日期FIFO；
            // 已消耗整根直接进入共挤生产，仅缺口部分进入织造缺口计算
            TapeStockConsumer.ConsumptionResult stock = stockConsumer.consume(tapePartNumber, proc.getTapeModelSpec(), tapeMetersNeeded);
            BigDecimal shortfall = stock.getShortfall();

            ScheduleAdjustmentRequest.ItemAdjustment itemAdj = req.getItemAdjustments() != null ?
                    req.getItemAdjustments().stream().filter(a -> a.getFinishedPartNumber().equals(item.getFinishedPartNumber())).findFirst().orElse(null) : null;

            // 产能解析：人工覆盖 > 工艺库标准值 > MISSING_CAPACITY 熔断
            BigDecimal wCap = capSnapshot.resolveWeavingCapacity(tapePartNumber, item.getFinishedPartNumber(),
                    itemAdj != null ? itemAdj.getManualWeavingCapacity() : null);
            BigDecimal cCap = capSnapshot.resolveCoexCapacity(item.getFinishedPartNumber(), tapePartNumber,
                    itemAdj != null ? itemAdj.getManualCoexCapacity() : null);
            // 织造储备库存（同单订单逻辑）：仅作开工时点偏移，不计入 tapeMetersNeed；缺省 0 天与现状一致
            BigDecimal[] reserve = calcWeavingReserve(req.getWeavingReserveDays(), wCap, cCap);
            BigDecimal reserveMeters = reserve[0];
            int reserveAdvanceDays = reserve[1].intValue();
            BigDecimal changeoverDays = new BigDecimal("1");
            Integer delayDays = 1;

            LocalDateTime rawDeadline = item.getDeliveryDate() != null ? item.getDeliveryDate().atTime(23, 59, 59) : LocalDate.now().plusDays(30).atTime(23, 59, 59);
            LocalDateTime deadline = rawDeadline.minusDays(bufferDays);

            long availableHours = ChronoUnit.HOURS.between(now, deadline);
            if (availableHours <= 0) availableHours = 1;

            double wHoursNeeded = shortfall.compareTo(BigDecimal.ZERO) > 0 ? shortfall.divide(wCap, 4, RoundingMode.HALF_UP).doubleValue() * 24.0 : 0;
            double cHoursNeeded = finishedMeters.divide(cCap, 4, RoundingMode.HALF_UP).doubleValue() * 24.0;

            // 口径匹配：传完整规格字符串走区间判定（spec 区间落入 limit 区间）；
            // 候选为空时告警写入 timeline.conflictWarnings（多订单链路既有收集机制），随预览响应透传至前端
            String caliberSpec = proc.getFinishedModelSpec();
            List<WeavingMachineStatus> candidateWMachines = capacityMatcher.findBestWeavingMachinesBySpec(caliberSpec, allWMachines, timeline.getConflictWarnings());
            List<CoexLineStatus> candidateCLines = capacityMatcher.findBestCoexLinesBySpec(caliberSpec, allCLines, timeline.getConflictWarnings());

            // 计算织造需要的机台数
            int machineCount;
            if (itemAdj != null && itemAdj.getManualWeavingMachineCount() != null && itemAdj.getManualWeavingMachineCount() > 0) {
                machineCount = itemAdj.getManualWeavingMachineCount();
            } else {
                machineCount = wHoursNeeded > availableHours ? (int) Math.ceil(wHoursNeeded / availableHours) : 1;
            }

            // 计算共挤需要的产线数
            int lineCount;
            if (itemAdj != null && itemAdj.getManualCoexLineCount() != null && itemAdj.getManualCoexLineCount() > 0) {
                lineCount = itemAdj.getManualCoexLineCount();
            } else {
                lineCount = cHoursNeeded > availableHours ? (int) Math.ceil(cHoursNeeded / availableHours) : 1;
            }

            // 物理上限约束
            int maxMachines = candidateWMachines.size();
            int maxLines = candidateCLines.size();
            if (machineCount > maxMachines) machineCount = Math.max(1, maxMachines);
            if (lineCount > maxLines) lineCount = Math.max(1, maxLines);

            // splitShortfall 仅作用于新织造缺口部分（库存整根已消耗，不再均分到织造）；splitFinished 为共挤总米数
            BigDecimal splitShortfall = machineCount > 0 ? shortfall.divide(new BigDecimal(machineCount), 4, RoundingMode.HALF_UP) : shortfall;
            BigDecimal splitFinished = lineCount > 0 ? finishedMeters.divide(new BigDecimal(lineCount), 4, RoundingMode.HALF_UP) : finishedMeters;

            // 已消耗整根按长度序列分配到共挤产线
            TapeStockConsumer.RollDistribution rollDistribution = TapeStockConsumer.distributeByLine(stock.getRolls(), lineCount, splitFinished);

            // 支持指定机台/产线列表
            List<WeavingMachineStatus> selectedWMachines = new ArrayList<>();
            List<CoexLineStatus> selectedCLines = new ArrayList<>();

            if (itemAdj != null && itemAdj.getAssignedMachineIds() != null && !itemAdj.getAssignedMachineIds().isEmpty()) {
                for (String mid : itemAdj.getAssignedMachineIds()) {
                    candidateWMachines.stream().filter(m -> m.getMachineId().equals(mid)).findFirst().ifPresent(selectedWMachines::add);
                }
            }
            if (itemAdj != null && itemAdj.getAssignedLineIds() != null && !itemAdj.getAssignedLineIds().isEmpty()) {
                for (String lid : itemAdj.getAssignedLineIds()) {
                    candidateCLines.stream().filter(l -> l.getLineId().equals(lid)).findFirst().ifPresent(selectedCLines::add);
                }
            }

            // 🌟 性能优化：O(1) 查找 orderId，替代原先的 O(N) stream 查找
            String orderId = partNumberToOrderId.getOrDefault(item.getFinishedPartNumber(), orderIds.get(0));

            Set<String> usedW = new HashSet<>();
            Set<String> usedC = new HashSet<>();
            // 需求6：按分配顺序收集织造机台ID，供共挤换带坯模拟使用
            List<String> weavingMachineIds = new ArrayList<>();

            // 织造独立分配
            for (int i = 0; i < machineCount; i++) {
                WeavingMachineStatus wm;
                if (i < selectedWMachines.size()) {
                    wm = selectedWMachines.get(i);
                } else {
                    wm = candidateWMachines.stream()
                            .filter(m -> !usedW.contains(m.getMachineId()))
                            .max(Comparator.comparingInt(m -> {
                                int score = capacityMatcher.scoreWeavingMachine(m, proc.getWarpSpec(), allWMachines);
                                LocalDateTime avail = timeline.getMachineAvailableTime(m.getMachineId(), now);
                                long penalty = ChronoUnit.HOURS.between(now, avail);
                                score -= (int) Math.min(penalty, 100);
                                return score;
                            })).orElse(null);
                }
                if (wm != null) {
                    usedW.add(wm.getMachineId());
                    weavingMachineIds.add(wm.getMachineId());
                }

                LocalDateTime machineAvailableTime = wm != null ? timeline.getMachineAvailableTime(wm.getMachineId(), now) : now;

                BigDecimal splitWHours = splitShortfall.divide(wCap, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("24"));
                LocalDateTime weavingEnd = deadline.minusDays(weaveAdvance);
                // 储备偏移：织造起点相对共挤再提前 reserveAdvanceDays 天（缺省 0 天时无偏移）
                LocalDateTime weavingStart = weavingEnd.minusMinutes(splitWHours.multiply(new BigDecimal("60")).longValue()).minusDays(reserveAdvanceDays);

                if (shortfall.compareTo(BigDecimal.ZERO) > 0 && weavingStart.isBefore(machineAvailableTime)) {
                    long shift = ChronoUnit.MINUTES.between(weavingStart, machineAvailableTime);
                    weavingStart = machineAvailableTime;
                    weavingEnd = weavingEnd.plusMinutes(shift);
                    timeline.addConflictWarning("机台 " + (wm != null ? wm.getMachineId() : "N/A") + " 被占用，织造延至 " + weavingStart);
                }
                if (weavingStart.isBefore(now)) {
                    long shiftMinutes = ChronoUnit.MINUTES.between(weavingStart, now);
                    weavingStart = weavingStart.plusMinutes(shiftMinutes);
                    weavingEnd = weavingEnd.plusMinutes(shiftMinutes);
                }

                if (wm != null && shortfall.compareTo(BigDecimal.ZERO) > 0) {
                    timeline.updateMachineTimeline(wm.getMachineId(), weavingEnd);
                }

                ScheduleDates wDates = new ScheduleDates();
                wDates.startDate = shortfall.compareTo(BigDecimal.ZERO) > 0 ? weavingStart : null;
                wDates.endDate = shortfall.compareTo(BigDecimal.ZERO) > 0 ? weavingEnd : null;
                wDates.algoWeavingCapacity = wCap;
                wDates.algoChangeoverDays = changeoverDays;
                ScheduleDates cDates = new ScheduleDates();
                cDates.algoCoexCapacity = cCap;
                cDates.algoDelayDays = delayDays;

                Map<String, Object> draftItem = buildDraftView(item.getFinishedPartNumber(), tapePartNumber, proc.getWarpSpec(), proc.getWeftSpec(), proc.getFinishedModelSpec(), proc.getTapeModelSpec(), splitFinished, splitShortfall, wDates, cDates, orderId, Collections.emptyList(), null, reserveMeters, reserveAdvanceDays);
                draftItem.put("plannedMachine", wm != null ? wm.getMachineId() : null);
                draftItem.put("plannedLine", null);
                draftItem.put("allocationType", "weaving");
                allResults.add(draftItem);

                if (wDates.startDate != null && wDates.startDate.isBefore(overallStartDate)) overallStartDate = wDates.startDate;
            }

            // 共挤独立分配
            for (int i = 0; i < lineCount; i++) {
                CoexLineStatus cl;
                if (i < selectedCLines.size()) {
                    cl = selectedCLines.get(i);
                } else {
                    cl = candidateCLines.stream()
                            .filter(l -> !usedC.contains(l.getLineId()))
                            .min(Comparator.comparing(l -> timeline.getLineAvailableTime(l.getLineId(), now)))
                            .orElse(null);
                }
                if (cl != null) usedC.add(cl.getLineId());

                LocalDateTime lineAvailableTime = cl != null ? timeline.getLineAvailableTime(cl.getLineId(), now) : now;

                BigDecimal splitCHours = splitFinished.divide(cCap, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("24"));
                LocalDateTime coexEnd = deadline;
                LocalDateTime coexStart = coexEnd.minusMinutes(splitCHours.multiply(new BigDecimal("60")).longValue());

                if (coexStart.isBefore(lineAvailableTime)) {
                    long shift = ChronoUnit.MINUTES.between(coexStart, lineAvailableTime);
                    coexStart = lineAvailableTime;
                    coexEnd = coexEnd.plusMinutes(shift);
                    timeline.addConflictWarning("产线 " + (cl != null ? cl.getLineId() : "N/A") + " 被占用，共挤延至 " + coexStart);
                }
                if (coexStart.isBefore(now)) {
                    long shiftMinutes = ChronoUnit.MINUTES.between(coexStart, now);
                    coexStart = coexStart.plusMinutes(shiftMinutes);
                    coexEnd = coexEnd.plusMinutes(shiftMinutes);
                }

                if (cl != null) {
                    timeline.updateLineTimeline(cl.getLineId(), coexEnd);
                }

                ScheduleDates wDates = new ScheduleDates();
                wDates.algoWeavingCapacity = wCap;
                wDates.algoChangeoverDays = changeoverDays;
                ScheduleDates cDates = new ScheduleDates();
                cDates.startDate = coexStart;
                cDates.endDate = coexEnd;
                cDates.algoCoexCapacity = cCap;
                cDates.algoDelayDays = delayDays;

                Map<String, Object> draftItem = buildDraftView(item.getFinishedPartNumber(), tapePartNumber, proc.getWarpSpec(), proc.getWeftSpec(), proc.getFinishedModelSpec(), proc.getTapeModelSpec(), splitFinished, splitShortfall, wDates, cDates, orderId,
                        rollDistribution.byLine.get(i), i == rollDistribution.lastRollLineIndex ? stock.getSurplusMeters() : null, reserveMeters, reserveAdvanceDays);
                draftItem.put("plannedMachine", null);
                draftItem.put("plannedLine", cl != null ? cl.getLineId() : null);
                draftItem.put("allocationType", "coex");
                // 需求6：模拟共挤中途换带坯时点（仅挂载 draftItem 展示，不落库），告警写入 timeline.conflictWarnings
                draftItem.put("tapeChangeEvents", TapeSupplySimulator.simulateTapeChanges(
                        cl != null ? cl.getLineId() : null, coexStart, cCap, wCap, reserveMeters,
                        buildLineSupplySegments(rollDistribution.byLine.get(i), weavingMachineIds, lineCount, i, splitShortfall),
                        timeline.getConflictWarnings()));
                allResults.add(draftItem);

                if (cDates.startDate.isBefore(overallStartDate)) overallStartDate = cDates.startDate;
                if (cDates.endDate.isAfter(overallEndDate)) overallEndDate = cDates.endDate;
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("results", allResults);
        result.put("overallStartDate", overallStartDate != LocalDateTime.MAX ? overallStartDate.toString() : null);
        result.put("overallEndDate", overallEndDate != LocalDateTime.MIN ? overallEndDate.toString() : null);
        result.put("conflictWarnings", timeline.getConflictWarnings());
        return result;
    }

    // ================ 内部数据结构 ================

    /**
     * 需求6：构建某条共挤产线的带坯供给段序列（供 TapeSupplySimulator 递推切换时点）。
     * 顺序：先库存整根（rollDistribution 已按产线分配，{tapeCode, meters}），后织造机台。
     * 机台→产线映射：machineCount 与 lineCount 独立，按 j*lineCount/machineCount 比例归属，
     * 支持 2对1、3对2 等任意组合；每台机台可供米数 = splitShortfall 配额。
     */
    private List<TapeSupplySimulator.SupplySegment> buildLineSupplySegments(
            List<Map<String, Object>> lineRolls, List<String> weavingMachineIds,
            int lineCount, int lineIndex, BigDecimal splitShortfall) {
        List<TapeSupplySimulator.SupplySegment> supply = new ArrayList<>();
        if (lineRolls != null) {
            for (Map<String, Object> roll : lineRolls) {
                Object meters = roll.get("meters");
                supply.add(TapeSupplySimulator.SupplySegment.stock(
                        String.valueOf(roll.get("tapeCode")),
                        meters instanceof BigDecimal ? (BigDecimal) meters : BigDecimal.ZERO));
            }
        }
        int bound = weavingMachineIds != null ? weavingMachineIds.size() : 0;
        int lanes = Math.max(1, lineCount);
        for (int j = 0; j < bound; j++) {
            if (bound > 0 && (j * lanes) / bound != lineIndex) continue;
            supply.add(TapeSupplySimulator.SupplySegment.machine(weavingMachineIds.get(j), splitShortfall));
        }
        return supply;
    }

    private static class ScheduleDates {
        LocalDateTime startDate;
        LocalDateTime endDate;
        BigDecimal algoWeavingCapacity;
        BigDecimal algoCoexCapacity;
        BigDecimal algoChangeoverDays;
        Integer algoDelayDays;
    }

    private Map<String, Object> buildDraftView(String fPn, String tPn, String warp, String weft, String fSpec, String tSpec,
                                                BigDecimal fMeters, BigDecimal tNeed,
                                                ScheduleDates w, ScheduleDates c, String orderId,
                                                List<Map<String, Object>> consumedTapeCodes, BigDecimal surplusMeters,
                                                BigDecimal reserveMeters, Integer reserveAdvanceDays) {
        Map<String, Object> m = new HashMap<>();
        m.put("orderId", orderId);
        m.put("finishedPartNumber", fPn);
        m.put("tapePartNumber", tPn);
        m.put("warpSpec", warp);
        m.put("weftSpec", weft);
        m.put("finishedModelSpec", fSpec);
        m.put("tapeModelSpec", tSpec);
        m.put("finishedMeters", fMeters);
        m.put("tapeMetersNeed", tNeed);
        m.put("weavingStart", w.startDate != null ? w.startDate.toString() : null);
        m.put("weavingEnd", w.endDate != null ? w.endDate.toString() : null);
        m.put("coexStart", c.startDate != null ? c.startDate.toString() : null);
        m.put("coexEnd", c.endDate != null ? c.endDate.toString() : null);
        m.put("weavingCapacity", w.algoWeavingCapacity);
        m.put("coexCapacity", c.algoCoexCapacity);
        m.put("changeoverDays", w.algoChangeoverDays);
        m.put("startDelay", c.algoDelayDays);
        // 织造储备库存展示字段：储备米数与对应提前开工天数（缺省 0）
        m.put("reserveMeters", reserveMeters);
        m.put("reserveAdvanceDays", reserveAdvanceDays);
        // 库存整根消耗清单：[{tapeCode, meters}]；超额米数明示标注，不静默截断
        m.put("consumedTapeCodes", consumedTapeCodes != null ? consumedTapeCodes : Collections.emptyList());
        m.put("surplusMeters", surplusMeters);
        if (surplusMeters != null && surplusMeters.compareTo(BigDecimal.ZERO) > 0) {
            m.put("consumptionRemark", "库存最后一根整根投入超出需求 " + surplusMeters.stripTrailingZeros().toPlainString() + " 米（超额未截断，如实计入该产线投入）");
        }
        return m;
    }

    private BigDecimal calcFinishedMeters(ProductionOrder item) {
        if (item.getTotalLength() != null && item.getTotalLength().compareTo(BigDecimal.ZERO) > 0) {
            return item.getTotalLength();
        }
        if (item.getMetersPerRoll() != null && item.getRollCount() != null && item.getRollCount() > 0) {
            return item.getMetersPerRoll().multiply(new BigDecimal(item.getRollCount()));
        }
        return null;
    }

    /**
     * 织造储备库存计算：reserveMeters = cCap × reserveDays，extraAdvanceDays = ceil(reserveMeters / wCap)。
     * reserveDays 为 null/≤0 或 wCap≤0 时返回 [0, 0]，保证缺省路径与原逻辑逐字节一致。
     * 返回数组：[0]=储备米数，[1]=额外提前开工天数。
     */
    static BigDecimal[] calcWeavingReserve(Integer reserveDays, BigDecimal wCap, BigDecimal cCap) {
        if (reserveDays == null || reserveDays <= 0 || wCap == null || wCap.compareTo(BigDecimal.ZERO) <= 0 || cCap == null) {
            return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO};
        }
        BigDecimal reserveMeters = cCap.multiply(new BigDecimal(reserveDays));
        BigDecimal extraAdvanceDays = reserveMeters.divide(wCap, 0, RoundingMode.CEILING);
        return new BigDecimal[]{reserveMeters, extraAdvanceDays};
    }

}
