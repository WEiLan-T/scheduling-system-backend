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
    private final VirtualWarehouseRepo warehouseRepo;
    private final WeavingDailyLogRepo weavingLogRepo;
    private final CoexDailyLogRepo coexLogRepo;
    private final ProductProcessRepo processRepo;
    private final WeavingMachineStatusRepo weavingStatusRepo;
    private final CoexLineStatusRepo coexStatusRepo;
    private final EstimatedProductionScheduleRepo scheduleRepo;

    public SchedulingEngine(CapacityMatcher capacityMatcher,
                            ResourceAllocator resourceAllocator,
                            ProductionOrderRepo orderRepo,
                            VirtualWarehouseRepo warehouseRepo,
                            WeavingDailyLogRepo weavingLogRepo,
                            CoexDailyLogRepo coexLogRepo,
                            ProductProcessRepo processRepo,
                            WeavingMachineStatusRepo weavingStatusRepo,
                            CoexLineStatusRepo coexStatusRepo,
                            EstimatedProductionScheduleRepo scheduleRepo) {
        this.capacityMatcher = capacityMatcher;
        this.resourceAllocator = resourceAllocator;
        this.orderRepo = orderRepo;
        this.warehouseRepo = warehouseRepo;
        this.weavingLogRepo = weavingLogRepo;
        this.coexLogRepo = coexLogRepo;
        this.processRepo = processRepo;
        this.weavingStatusRepo = weavingStatusRepo;
        this.coexStatusRepo = coexStatusRepo;
        this.scheduleRepo = scheduleRepo;
    }

    // ================ 单订单排产 ================
    public Map<String, Object> previewSingleOrder(ScheduleAdjustmentRequest req, String currentUser) {
        List<ProductionOrder> orders = (req.getDraftOrders() != null && !req.getDraftOrders().isEmpty())
                ? req.getDraftOrders() : orderRepo.findByOrderId(req.getOrderId());
        if (orders == null || orders.isEmpty()) throw new RuntimeException("查无此订单，请核对订单号！");

        // 🌟 性能优化：一次性加载所有平均产能到缓存 Map
        Map<String, BigDecimal> weavingCapCache = loadWeavingCapCache();
        Map<String, BigDecimal> coexCapCache = loadCoexCapCache();

        int bufferDays = req.getGlobalBufferDays() != null ? req.getGlobalBufferDays() : 3;
        int weaveAdvance = req.getWeavingAdvanceDays() != null ? req.getWeavingAdvanceDays() : 2;

        LocalDateTime overallStartDate = LocalDateTime.MAX;
        LocalDateTime overallEndDate = LocalDateTime.MIN;
        List<Map<String, Object>> itemSchedules = new ArrayList<>();

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

            BigDecimal currentInventory = warehouseRepo.findByFinishedPartNumber(item.getFinishedPartNumber()).stream()
                    .map(w -> w.getCurrentStockMeters() != null ? w.getCurrentStockMeters() : BigDecimal.ZERO).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal shortfall = tapeMetersNeeded.subtract(currentInventory).max(BigDecimal.ZERO);

            ScheduleAdjustmentRequest.ItemAdjustment itemAdj = req.getItemAdjustments() != null ?
                    req.getItemAdjustments().stream().filter(a -> a.getFinishedPartNumber().equals(item.getFinishedPartNumber())).findFirst().orElse(null) : null;

            BigDecimal wCap = getWeavingAvgCap(tapePartNumber, weavingCapCache);
            BigDecimal cCap = getCoexAvgCap(item.getFinishedPartNumber(), coexCapCache);
            BigDecimal changeoverDays = new BigDecimal("1");
            Integer delayDays = 1;

            if (itemAdj != null) {
                if (itemAdj.getManualWeavingCapacity() != null && itemAdj.getManualWeavingCapacity().compareTo(BigDecimal.ZERO) > 0) wCap = itemAdj.getManualWeavingCapacity();
                if (itemAdj.getManualCoexCapacity() != null && itemAdj.getManualCoexCapacity().compareTo(BigDecimal.ZERO) > 0) cCap = itemAdj.getManualCoexCapacity();
            }
            if (wCap.compareTo(BigDecimal.ZERO) <= 0 || cCap.compareTo(BigDecimal.ZERO) <= 0) throw new RuntimeException("MISSING_CAPACITY:" + item.getFinishedPartNumber() + ":" + tapePartNumber);

            LocalDateTime rawDeadline = item.getDeliveryDate() != null ? item.getDeliveryDate().atTime(23, 59, 59) : LocalDate.now().plusDays(30).atTime(23, 59, 59);
            LocalDateTime deadline = rawDeadline.minusDays(bufferDays);

            LocalDateTime now = LocalDateTime.now();
            long availableHours = ChronoUnit.HOURS.between(now, deadline);
            if (availableHours <= 0) availableHours = 1;

            double wHoursNeeded = shortfall.compareTo(BigDecimal.ZERO) > 0 ? shortfall.divide(wCap, 4, RoundingMode.HALF_UP).doubleValue() * 24.0 : 0;
            double cHoursNeeded = finishedMeters.divide(cCap, 4, RoundingMode.HALF_UP).doubleValue() * 24.0;

            Double caliber = capacityMatcher.extractCaliber(proc.getFinishedModelSpec());
            List<WeavingMachineStatus> candidateWMachines = capacityMatcher.findBestWeavingMachines(caliber, allWMachines);
            List<CoexLineStatus> candidateCLines = capacityMatcher.findBestCoexLines(caliber, allCLines);

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

            BigDecimal splitShortfall = machineCount > 0 ? shortfall.divide(new BigDecimal(machineCount), 4, RoundingMode.HALF_UP) : shortfall;
            BigDecimal splitFinished = lineCount > 0 ? finishedMeters.divide(new BigDecimal(lineCount), 4, RoundingMode.HALF_UP) : finishedMeters;

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
                if (wm != null) usedW.add(wm.getMachineId());

                BigDecimal splitWHours = splitShortfall.divide(wCap, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("24"));
                LocalDateTime weavingEnd = deadline.minusDays(weaveAdvance);
                LocalDateTime weavingStart = weavingEnd.minusMinutes(splitWHours.multiply(new BigDecimal("60")).longValue());

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

                Map<String, Object> draftItem = buildDraftView(item.getFinishedPartNumber(), tapePartNumber, proc.getWarpSpec(), proc.getWeftSpec(), proc.getFinishedModelSpec(), proc.getTapeModelSpec(), splitFinished, splitShortfall, wDates, cDates, req.getOrderId());
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

                Map<String, Object> draftItem = buildDraftView(item.getFinishedPartNumber(), tapePartNumber, proc.getWarpSpec(), proc.getWeftSpec(), proc.getFinishedModelSpec(), proc.getTapeModelSpec(), splitFinished, splitShortfall, wDates, cDates, req.getOrderId());
                draftItem.put("plannedMachine", null);
                draftItem.put("plannedLine", cl != null ? cl.getLineId() : null);
                draftItem.put("allocationType", "coex");
                itemSchedules.add(draftItem);

                if (cDates.startDate.isBefore(overallStartDate)) overallStartDate = cDates.startDate;
                if (cDates.endDate.isAfter(overallEndDate)) overallEndDate = cDates.endDate;
            }
        }

        Map<String, Object> draft = new HashMap<>();
        draft.put("orderId", req.getOrderId()); draft.put("overallStartDate", overallStartDate.toString()); draft.put("overallEndDate", overallEndDate.toString()); draft.put("totalDays", ChronoUnit.DAYS.between(overallStartDate.toLocalDate(), overallEndDate.toLocalDate()) + 1); draft.put("details", itemSchedules);
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

        Map<String, BigDecimal> weavingCapCache = loadWeavingCapCache();
        Map<String, BigDecimal> coexCapCache = loadCoexCapCache();

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

            BigDecimal currentInventory = warehouseRepo.findByFinishedPartNumber(item.getFinishedPartNumber()).stream()
                    .map(w -> w.getCurrentStockMeters() != null ? w.getCurrentStockMeters() : BigDecimal.ZERO).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal shortfall = tapeMetersNeeded.subtract(currentInventory).max(BigDecimal.ZERO);

            ScheduleAdjustmentRequest.ItemAdjustment itemAdj = req.getItemAdjustments() != null ?
                    req.getItemAdjustments().stream().filter(a -> a.getFinishedPartNumber().equals(item.getFinishedPartNumber())).findFirst().orElse(null) : null;

            BigDecimal wCap = getWeavingAvgCap(tapePartNumber, weavingCapCache);
            BigDecimal cCap = getCoexAvgCap(item.getFinishedPartNumber(), coexCapCache);
            BigDecimal changeoverDays = new BigDecimal("1");
            Integer delayDays = 1;

            if (itemAdj != null) {
                if (itemAdj.getManualWeavingCapacity() != null && itemAdj.getManualWeavingCapacity().compareTo(BigDecimal.ZERO) > 0) wCap = itemAdj.getManualWeavingCapacity();
                if (itemAdj.getManualCoexCapacity() != null && itemAdj.getManualCoexCapacity().compareTo(BigDecimal.ZERO) > 0) cCap = itemAdj.getManualCoexCapacity();
            }
            if (wCap.compareTo(BigDecimal.ZERO) <= 0 || cCap.compareTo(BigDecimal.ZERO) <= 0) throw new RuntimeException("MISSING_CAPACITY:" + item.getFinishedPartNumber() + ":" + tapePartNumber);

            LocalDateTime rawDeadline = item.getDeliveryDate() != null ? item.getDeliveryDate().atTime(23, 59, 59) : LocalDate.now().plusDays(30).atTime(23, 59, 59);
            LocalDateTime deadline = rawDeadline.minusDays(bufferDays);

            long availableHours = ChronoUnit.HOURS.between(now, deadline);
            if (availableHours <= 0) availableHours = 1;

            double wHoursNeeded = shortfall.compareTo(BigDecimal.ZERO) > 0 ? shortfall.divide(wCap, 4, RoundingMode.HALF_UP).doubleValue() * 24.0 : 0;
            double cHoursNeeded = finishedMeters.divide(cCap, 4, RoundingMode.HALF_UP).doubleValue() * 24.0;

            Double caliber = capacityMatcher.extractCaliber(proc.getFinishedModelSpec());
            List<WeavingMachineStatus> candidateWMachines = capacityMatcher.findBestWeavingMachines(caliber, allWMachines);
            List<CoexLineStatus> candidateCLines = capacityMatcher.findBestCoexLines(caliber, allCLines);

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

            BigDecimal splitShortfall = machineCount > 0 ? shortfall.divide(new BigDecimal(machineCount), 4, RoundingMode.HALF_UP) : shortfall;
            BigDecimal splitFinished = lineCount > 0 ? finishedMeters.divide(new BigDecimal(lineCount), 4, RoundingMode.HALF_UP) : finishedMeters;

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
                if (wm != null) usedW.add(wm.getMachineId());

                LocalDateTime machineAvailableTime = wm != null ? timeline.getMachineAvailableTime(wm.getMachineId(), now) : now;

                BigDecimal splitWHours = splitShortfall.divide(wCap, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("24"));
                LocalDateTime weavingEnd = deadline.minusDays(weaveAdvance);
                LocalDateTime weavingStart = weavingEnd.minusMinutes(splitWHours.multiply(new BigDecimal("60")).longValue());

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

                Map<String, Object> draftItem = buildDraftView(item.getFinishedPartNumber(), tapePartNumber, proc.getWarpSpec(), proc.getWeftSpec(), proc.getFinishedModelSpec(), proc.getTapeModelSpec(), splitFinished, splitShortfall, wDates, cDates, orderId);
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

                Map<String, Object> draftItem = buildDraftView(item.getFinishedPartNumber(), tapePartNumber, proc.getWarpSpec(), proc.getWeftSpec(), proc.getFinishedModelSpec(), proc.getTapeModelSpec(), splitFinished, splitShortfall, wDates, cDates, orderId);
                draftItem.put("plannedMachine", null);
                draftItem.put("plannedLine", cl != null ? cl.getLineId() : null);
                draftItem.put("allocationType", "coex");
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
                                                ScheduleDates w, ScheduleDates c, String orderId) {
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
        m.put("coexStart", c.startDate.toString());
        m.put("coexEnd", c.endDate.toString());
        m.put("weavingCapacity", w.algoWeavingCapacity);
        m.put("coexCapacity", c.algoCoexCapacity);
        m.put("changeoverDays", w.algoChangeoverDays);
        m.put("startDelay", c.algoDelayDays);
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

    // ================ 🌟 产能缓存加载方法 ================

    private Map<String, BigDecimal> loadWeavingCapCache() {
        Map<String, BigDecimal> cache = new HashMap<>();
        weavingLogRepo.findAvgCapacityGroupByTapePartNumber().forEach(row -> {
            cache.put((String) row[0], toBigDecimal(row[1]).multiply(new BigDecimal("2")));
        });
        return cache;
    }

    private Map<String, BigDecimal> loadCoexCapCache() {
        Map<String, BigDecimal> cache = new HashMap<>();
        coexLogRepo.findAvgCapacityGroupByFinishedPartNumber().forEach(row -> {
            cache.put((String) row[0], toBigDecimal(row[1]));
        });
        return cache;
    }

    private BigDecimal getWeavingAvgCap(String tapePn, Map<String, BigDecimal> cache) {
        if (cache.containsKey(tapePn)) return cache.get(tapePn);
        // fallback: 原始查询逻辑
        List<WeavingDailyLog> logs = weavingLogRepo.findByTapePartNumber(tapePn);
        if (logs == null || logs.isEmpty()) return BigDecimal.ZERO;
        return logs.stream().map(WeavingDailyLog::getCapacityPerDay).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(new BigDecimal(logs.size()), 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("2"));
    }

    private BigDecimal getCoexAvgCap(String finishedPn, Map<String, BigDecimal> cache) {
        if (cache.containsKey(finishedPn)) return cache.get(finishedPn);
        // fallback: 原始查询逻辑
        List<CoexDailyLog> logs = coexLogRepo.findByFinishedPartNumber(finishedPn);
        if (logs == null || logs.isEmpty()) return BigDecimal.ZERO;
        return logs.stream().map(CoexDailyLog::getCapacityPerDay).filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(new BigDecimal(logs.size()), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal) return (BigDecimal) value;
        if (value instanceof Number) return new BigDecimal(value.toString());
        return BigDecimal.ZERO;
    }
}
