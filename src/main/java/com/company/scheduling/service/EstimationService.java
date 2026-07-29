package com.company.scheduling.service;

import com.company.scheduling.domain.*;
import com.company.scheduling.dto.MultiOrderScheduleRequest;
import com.company.scheduling.dto.ScheduleAdjustmentRequest;
import com.company.scheduling.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class EstimationService {

    private final MasterProductionPlanRepo planRepo;
    private final EstimatedProductionScheduleRepo scheduleRepo;
    private final ProductionOrderRepo orderRepo;
    private final VirtualWarehouseRepo warehouseRepo;
    private final WeavingDailyLogRepo weavingLogRepo;
    private final CoexDailyLogRepo coexLogRepo;
    private final ProductProcessRepo processRepo;
    private final WeavingMachineStatusRepo weavingStatusRepo;
    private final CoexLineStatusRepo coexStatusRepo;

    public EstimationService(MasterProductionPlanRepo planRepo, EstimatedProductionScheduleRepo scheduleRepo,
                             ProductionOrderRepo orderRepo, VirtualWarehouseRepo warehouseRepo,
                             WeavingDailyLogRepo weavingLogRepo, CoexDailyLogRepo coexLogRepo, ProductProcessRepo processRepo,
                             WeavingMachineStatusRepo weavingStatusRepo, CoexLineStatusRepo coexStatusRepo) {
        this.planRepo = planRepo; this.scheduleRepo = scheduleRepo; this.orderRepo = orderRepo;
        this.warehouseRepo = warehouseRepo; this.weavingLogRepo = weavingLogRepo;
        this.coexLogRepo = coexLogRepo; this.processRepo = processRepo;
        this.weavingStatusRepo = weavingStatusRepo; this.coexStatusRepo = coexStatusRepo;
    }

    // ================ 核心工具：口径解析与匹配 ================
    private Double extractCaliber(String spec) {
        if (spec == null || spec.trim().isEmpty()) return null;
        String[] parts = spec.split("-");
        String lastPart = parts[parts.length - 1].replaceAll("[^0-9.]", ""); // 提取型号 "-" 后的数字
        try { return Double.parseDouble(lastPart); } catch (Exception e) { return null; }
    }

    private boolean isCaliberMatch(Double caliber, String limit) {
        if (caliber == null || limit == null || limit.trim().isEmpty()) return true;
        try {
            String[] parts = limit.split("-");
            double min = Double.parseDouble(parts[0]);
            double max = parts.length > 1 ? Double.parseDouble(parts[1]) : min;
            return caliber >= min && caliber <= max;
        } catch (Exception e) { return true; }
    }

    // ================ 核心工具：剥离并匹配车间编号 ================
    private Integer extractWorkshopNumber(String workshopId) {
        if (workshopId == null) return null;
        if (workshopId.contains("1") || workshopId.contains("一")) return 1;
        if (workshopId.contains("2") || workshopId.contains("二")) return 2;
        if (workshopId.contains("3") || workshopId.contains("三")) return 3;
        return null;
    }

    // ================ 启发式打分机制 ================
    private List<WeavingMachineStatus> findBestWeavingMachines(Double caliber, List<WeavingMachineStatus> allMachines) {
        return allMachines.stream().filter(m -> isCaliberMatch(caliber, m.getCaliberLimit())).collect(Collectors.toList());
    }

    private int scoreWeavingMachine(WeavingMachineStatus m, String warpSpec, List<WeavingMachineStatus> all) {
        int score = 0;
        boolean isIdle = "空闲".equals(m.getMachineStatus());
        if (warpSpec != null && warpSpec.equals(m.getWarpSpec())) score += 100; // 同经线极度优先

        if (isIdle) {
            score += 20;
            if (m.getAdjacentMachine() != null && !m.getAdjacentMachine().trim().isEmpty()) {
                boolean neighborActive = all.stream().anyMatch(n -> m.getAdjacentMachine().equals(n.getMachineId()) && "在产".equals(n.getMachineStatus()));
                if (neighborActive) score += 50; // 一人多机就近安排
            }
        } else {
            score -= 50;
        }
        return score;
    }

    private List<CoexLineStatus> findBestCoexLines(Double caliber, List<CoexLineStatus> allLines) {
        return allLines.stream()
                .filter(l -> isCaliberMatch(caliber, l.getCaliberLimit()))
                .sorted((a, b) -> Integer.compare("空闲".equals(b.getLineStatus()) ? 1 : 0, "空闲".equals(a.getLineStatus()) ? 1 : 0))
                .collect(Collectors.toList());
    }

    public Map<String, Object> previewSchedule(ScheduleAdjustmentRequest req, String currentUser) {
        List<ProductionOrder> orders = (req.getDraftOrders() != null && !req.getDraftOrders().isEmpty())
                ? req.getDraftOrders() : orderRepo.findByOrderId(req.getOrderId());
        if (orders == null || orders.isEmpty()) throw new RuntimeException("查无此订单，请核对订单号！");

        // 读取人工调整参数（若前端未传则使用默认值）
        int bufferDays = req.getGlobalBufferDays() != null ? req.getGlobalBufferDays() : 3;
        int weaveAdvance = req.getWeavingAdvanceDays() != null ? req.getWeavingAdvanceDays() : 2;

        LocalDateTime overallStartDate = LocalDateTime.MAX;
        LocalDateTime overallEndDate = LocalDateTime.MIN;
        List<Map<String, Object>> itemSchedules = new ArrayList<>();

        List<WeavingMachineStatus> allWMachines = weavingStatusRepo.findAll();
        List<CoexLineStatus> allCLines = coexStatusRepo.findAll();

        for (ProductionOrder item : orders) {
            // 🌟 优化3：织造预计生产量调整为与共挤一致，取消 1.10 系数
            BigDecimal finishedMeters = item.getMetersPerRoll().multiply(new BigDecimal(item.getRollCount()));
            BigDecimal tapeMetersNeeded = finishedMeters;

            ProductProcess proc = processRepo.findByFinishedPartNumber(item.getFinishedPartNumber())
                    .orElseThrow(() -> new RuntimeException("MISSING_PROCESS:" + item.getFinishedPartNumber()));
            String tapePartNumber = proc.getTapePartNumber();

            BigDecimal currentInventory = warehouseRepo.findByFinishedPartNumber(item.getFinishedPartNumber()).stream()
                    .map(w -> w.getCurrentStockMeters() != null ? w.getCurrentStockMeters() : BigDecimal.ZERO).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal shortfall = tapeMetersNeeded.subtract(currentInventory).max(BigDecimal.ZERO);

            ScheduleAdjustmentRequest.ItemAdjustment itemAdj = req.getItemAdjustments() != null ?
                    req.getItemAdjustments().stream().filter(a -> a.getFinishedPartNumber().equals(item.getFinishedPartNumber())).findFirst().orElse(null) : null;

            BigDecimal wCap = getWeavingAvgCap(tapePartNumber);
            BigDecimal cCap = getCoexAvgCap(item.getFinishedPartNumber());
            BigDecimal changeoverDays = new BigDecimal("1");
            Integer delayDays = 1;

            if (itemAdj != null) {
                if (itemAdj.getManualWeavingCapacity() != null) wCap = itemAdj.getManualWeavingCapacity();
                if (itemAdj.getManualCoexCapacity() != null) cCap = itemAdj.getManualCoexCapacity();
            }
            if (wCap.compareTo(BigDecimal.ZERO) == 0 || cCap.compareTo(BigDecimal.ZERO) == 0) throw new RuntimeException("MISSING_CAPACITY:" + item.getFinishedPartNumber() + ":" + tapePartNumber);

            // ================= JIT 产能测算与排产重构 =================
            // 🌟 优化1：为所有规划留出 bufferDays 的时间
            LocalDateTime rawDeadline = item.getDeliveryDate() != null ? item.getDeliveryDate().atTime(23, 59, 59) : LocalDate.now().plusDays(30).atTime(23, 59, 59);
            LocalDateTime deadline = rawDeadline.minusDays(bufferDays);

            LocalDateTime now = LocalDateTime.now();
            long availableHours = ChronoUnit.HOURS.between(now, deadline);
            if (availableHours <= 0) availableHours = 1;

            double wHoursNeeded = shortfall.compareTo(BigDecimal.ZERO) > 0 ? shortfall.divide(wCap, 4, RoundingMode.HALF_UP).doubleValue() * 24.0 : 0;
            double cHoursNeeded = finishedMeters.divide(cCap, 4, RoundingMode.HALF_UP).doubleValue() * 24.0;
            int splitCount = 1;
            double maxHoursNeeded = Math.max(wHoursNeeded, cHoursNeeded);
            if (maxHoursNeeded > availableHours) splitCount = (int) Math.ceil(maxHoursNeeded / availableHours);

            Double caliber = extractCaliber(proc.getFinishedModelSpec());
            List<WeavingMachineStatus> candidateWMachines = findBestWeavingMachines(caliber, allWMachines);
            List<CoexLineStatus> candidateCLines = findBestCoexLines(caliber, allCLines);

            int maxPhysical = Math.max(1, Math.max(candidateWMachines.size(), candidateCLines.size()));
            if (splitCount > maxPhysical) splitCount = maxPhysical;

            BigDecimal splitFinished = finishedMeters.divide(new BigDecimal(splitCount), 4, RoundingMode.HALF_UP);
            BigDecimal splitShortfall = shortfall.divide(new BigDecimal(splitCount), 4, RoundingMode.HALF_UP);

            Set<String> usedW = new HashSet<>();
            Set<String> usedC = new HashSet<>();

            for (int i = 0; i < splitCount; i++) {
                // ... 车间号提取与分数匹配原逻辑保持不变 ...
                CoexLineStatus cl = candidateCLines.stream().filter(l -> !usedC.contains(l.getLineId())).findFirst().orElse(null);
                if (cl != null) usedC.add(cl.getLineId());
                Integer targetWs = cl != null ? extractWorkshopNumber(cl.getWorkshopId()) : null;

                WeavingMachineStatus wm = candidateWMachines.stream()
                        .filter(m -> !usedW.contains(m.getMachineId()))
                        .max(Comparator.comparingInt(m -> {
                            int score = scoreWeavingMachine(m, proc.getWarpSpec(), allWMachines);
                            Integer mWs = extractWorkshopNumber(m.getWorkshopId());
                            if (targetWs != null && targetWs.equals(mWs)) score += 80;
                            return score;
                        })).orElse(null);
                if (wm != null) usedW.add(wm.getMachineId());

                BigDecimal splitWHours = splitShortfall.divide(wCap, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("24"));
                BigDecimal splitCHours = splitFinished.divide(cCap, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("24"));

                // 🌟 优化2：将织造提前 2天（weaveAdvance）结束生产
                LocalDateTime coexEnd = deadline;
                LocalDateTime coexStart = coexEnd.minusMinutes(splitCHours.multiply(new BigDecimal("60")).longValue());

                LocalDateTime weavingEnd = coexEnd.minusDays(weaveAdvance);
                LocalDateTime weavingStart = weavingEnd.minusMinutes(splitWHours.multiply(new BigDecimal("60")).longValue());

                // 确保织造开机时间依然比共挤开机时间早至少 weaveAdvance 天
                if (shortfall.compareTo(BigDecimal.ZERO) > 0 && weavingStart.isAfter(coexStart.minusDays(weaveAdvance))) {
                    weavingStart = coexStart.minusDays(weaveAdvance);
                }

                if (weavingStart.isBefore(now) || coexStart.isBefore(now)) {
                    LocalDateTime earliest = weavingStart.isBefore(coexStart) ? weavingStart : coexStart;
                    long shiftMinutes = ChronoUnit.MINUTES.between(earliest, now);
                    weavingStart = weavingStart.plusMinutes(shiftMinutes); weavingEnd = weavingEnd.plusMinutes(shiftMinutes);
                    coexStart = coexStart.plusMinutes(shiftMinutes); coexEnd = coexEnd.plusMinutes(shiftMinutes);
                }

                ScheduleDates wDates = new ScheduleDates(); wDates.startDate = shortfall.compareTo(BigDecimal.ZERO) > 0 ? weavingStart : null; wDates.endDate = shortfall.compareTo(BigDecimal.ZERO) > 0 ? weavingEnd : null; wDates.algoWeavingCapacity = wCap; wDates.algoChangeoverDays = changeoverDays;
                ScheduleDates cDates = new ScheduleDates(); cDates.startDate = coexStart; cDates.endDate = coexEnd; cDates.algoCoexCapacity = cCap; cDates.algoDelayDays = delayDays;

                Map<String, Object> draftItem = buildDraftView(item.getFinishedPartNumber(), tapePartNumber, proc.getWarpSpec(), proc.getWeftSpec(), proc.getFinishedModelSpec(), proc.getTapeModelSpec(), splitFinished, splitShortfall, wDates, cDates, req.getOrderId());
                draftItem.put("plannedMachine", wm != null ? wm.getMachineId() : null);
                draftItem.put("plannedLine", cl != null ? cl.getLineId() : null);
                itemSchedules.add(draftItem);

                if (wDates.startDate != null && wDates.startDate.isBefore(overallStartDate)) overallStartDate = wDates.startDate;
                if (cDates.startDate.isBefore(overallStartDate)) overallStartDate = cDates.startDate;
                if (cDates.endDate.isAfter(overallEndDate)) overallEndDate = cDates.endDate;
            }
        }

        Map<String, Object> draft = new HashMap<>();
        draft.put("orderId", req.getOrderId()); draft.put("overallStartDate", overallStartDate.toString()); draft.put("overallEndDate", overallEndDate.toString()); draft.put("totalDays", ChronoUnit.DAYS.between(overallStartDate.toLocalDate(), overallEndDate.toLocalDate()) + 1); draft.put("details", itemSchedules);
        return draft;
    }

    // ================ 多订单并发排产引擎 ================
    public Map<String, Object> previewMultiOrderSchedule(MultiOrderScheduleRequest req, String currentUser) {
        List<String> orderIds = req.getOrderIds();
        if (orderIds == null || orderIds.isEmpty()) throw new RuntimeException("订单列表不能为空！");

        int bufferDays = req.getGlobalBufferDays() != null ? req.getGlobalBufferDays() : 3;
        int weaveAdvance = req.getWeavingAdvanceDays() != null ? req.getWeavingAdvanceDays() : 2;

        // 1. 收集所有订单并按交货期升序排序（贪心优先级）
        List<ProductionOrder> allOrders = new ArrayList<>();
        for (String orderId : orderIds) {
            List<ProductionOrder> orders = orderRepo.findByOrderId(orderId);
            if (orders != null) allOrders.addAll(orders);
        }
        if (allOrders.isEmpty()) throw new RuntimeException("查无订单，请核对订单号！");
        allOrders.sort(Comparator.comparing(o -> o.getDeliveryDate() != null ? o.getDeliveryDate() : LocalDate.now().plusYears(10)));

        // 2. 初始化资源占用时间线
        LocalDateTime now = LocalDateTime.now();
        Map<String, LocalDateTime> resourceTimeline = new HashMap<>();

        List<WeavingMachineStatus> allWMachines = weavingStatusRepo.findAll();
        List<CoexLineStatus> allCLines = coexStatusRepo.findAll();

        // 从已有排产初始化时间线
        for (WeavingMachineStatus wm : allWMachines) {
            List<EstimatedProductionSchedule> existing = scheduleRepo.findByWeavingMachineIdAndWeavingEndDateAfter(wm.getMachineId(), now);
            existing.stream()
                .filter(e -> e.getWeavingEndDate() != null)
                .max(Comparator.comparing(EstimatedProductionSchedule::getWeavingEndDate))
                .ifPresent(e -> resourceTimeline.put("W_" + wm.getMachineId(), e.getWeavingEndDate()));
        }
        for (CoexLineStatus cl : allCLines) {
            List<EstimatedProductionSchedule> existing = scheduleRepo.findByCoexLineIdAndCoexEndDateAfter(cl.getLineId(), now);
            existing.stream()
                .filter(e -> e.getCoexEndDate() != null)
                .max(Comparator.comparing(EstimatedProductionSchedule::getCoexEndDate))
                .ifPresent(e -> resourceTimeline.put("C_" + cl.getLineId(), e.getCoexEndDate()));
        }

        // 3. 逐订单排产
        LocalDateTime overallStartDate = LocalDateTime.MAX;
        LocalDateTime overallEndDate = LocalDateTime.MIN;
        List<Map<String, Object>> allResults = new ArrayList<>();
        List<String> conflictWarnings = new ArrayList<>();

        for (ProductionOrder item : allOrders) {
            BigDecimal finishedMeters = item.getMetersPerRoll().multiply(new BigDecimal(item.getRollCount()));
            BigDecimal tapeMetersNeeded = finishedMeters;

            ProductProcess proc = processRepo.findByFinishedPartNumber(item.getFinishedPartNumber())
                    .orElseThrow(() -> new RuntimeException("MISSING_PROCESS:" + item.getFinishedPartNumber()));
            String tapePartNumber = proc.getTapePartNumber();

            BigDecimal currentInventory = warehouseRepo.findByFinishedPartNumber(item.getFinishedPartNumber()).stream()
                    .map(w -> w.getCurrentStockMeters() != null ? w.getCurrentStockMeters() : BigDecimal.ZERO).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal shortfall = tapeMetersNeeded.subtract(currentInventory).max(BigDecimal.ZERO);

            ScheduleAdjustmentRequest.ItemAdjustment itemAdj = req.getItemAdjustments() != null ?
                    req.getItemAdjustments().stream().filter(a -> a.getFinishedPartNumber().equals(item.getFinishedPartNumber())).findFirst().orElse(null) : null;

            BigDecimal wCap = getWeavingAvgCap(tapePartNumber);
            BigDecimal cCap = getCoexAvgCap(item.getFinishedPartNumber());
            BigDecimal changeoverDays = new BigDecimal("1");
            Integer delayDays = 1;

            if (itemAdj != null) {
                if (itemAdj.getManualWeavingCapacity() != null) wCap = itemAdj.getManualWeavingCapacity();
                if (itemAdj.getManualCoexCapacity() != null) cCap = itemAdj.getManualCoexCapacity();
            }
            if (wCap.compareTo(BigDecimal.ZERO) == 0 || cCap.compareTo(BigDecimal.ZERO) == 0) throw new RuntimeException("MISSING_CAPACITY:" + item.getFinishedPartNumber() + ":" + tapePartNumber);

            LocalDateTime rawDeadline = item.getDeliveryDate() != null ? item.getDeliveryDate().atTime(23, 59, 59) : LocalDate.now().plusDays(30).atTime(23, 59, 59);
            LocalDateTime deadline = rawDeadline.minusDays(bufferDays);

            long availableHours = ChronoUnit.HOURS.between(now, deadline);
            if (availableHours <= 0) availableHours = 1;

            double wHoursNeeded = shortfall.compareTo(BigDecimal.ZERO) > 0 ? shortfall.divide(wCap, 4, RoundingMode.HALF_UP).doubleValue() * 24.0 : 0;
            double cHoursNeeded = finishedMeters.divide(cCap, 4, RoundingMode.HALF_UP).doubleValue() * 24.0;
            int splitCount = 1;
            double maxHoursNeeded = Math.max(wHoursNeeded, cHoursNeeded);
            if (maxHoursNeeded > availableHours) splitCount = (int) Math.ceil(maxHoursNeeded / availableHours);

            Double caliber = extractCaliber(proc.getFinishedModelSpec());
            List<WeavingMachineStatus> candidateWMachines = findBestWeavingMachines(caliber, allWMachines);
            List<CoexLineStatus> candidateCLines = findBestCoexLines(caliber, allCLines);

            int maxPhysical = Math.max(1, Math.max(candidateWMachines.size(), candidateCLines.size()));
            if (splitCount > maxPhysical) splitCount = maxPhysical;

            BigDecimal splitFinished = finishedMeters.divide(new BigDecimal(splitCount), 4, RoundingMode.HALF_UP);
            BigDecimal splitShortfall = shortfall.divide(new BigDecimal(splitCount), 4, RoundingMode.HALF_UP);

            Set<String> usedW = new HashSet<>();
            Set<String> usedC = new HashSet<>();

            for (int i = 0; i < splitCount; i++) {
                // 选择未被本订单占用且时间线最早可用的资源
                CoexLineStatus cl = candidateCLines.stream()
                        .filter(l -> !usedC.contains(l.getLineId()))
                        .min(Comparator.comparing(l -> resourceTimeline.getOrDefault("C_" + l.getLineId(), now)))
                        .orElse(null);
                if (cl != null) usedC.add(cl.getLineId());
                Integer targetWs = cl != null ? extractWorkshopNumber(cl.getWorkshopId()) : null;

                WeavingMachineStatus wm = candidateWMachines.stream()
                        .filter(m -> !usedW.contains(m.getMachineId()))
                        .max(Comparator.comparingInt(m -> {
                            int score = scoreWeavingMachine(m, proc.getWarpSpec(), allWMachines);
                            Integer mWs = extractWorkshopNumber(m.getWorkshopId());
                            if (targetWs != null && targetWs.equals(mWs)) score += 80;
                            // 优先选择时间线更早可用的机台
                            LocalDateTime avail = resourceTimeline.getOrDefault("W_" + m.getMachineId(), now);
                            long penalty = ChronoUnit.HOURS.between(now, avail);
                            score -= (int) Math.min(penalty, 100);
                            return score;
                        })).orElse(null);
                if (wm != null) usedW.add(wm.getMachineId());

                // 获取资源可用时间
                LocalDateTime machineAvailableTime = wm != null ? resourceTimeline.getOrDefault("W_" + wm.getMachineId(), now) : now;
                LocalDateTime lineAvailableTime = cl != null ? resourceTimeline.getOrDefault("C_" + cl.getLineId(), now) : now;

                BigDecimal splitWHours = splitShortfall.divide(wCap, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("24"));
                BigDecimal splitCHours = splitFinished.divide(cCap, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("24"));

                LocalDateTime coexEnd = deadline;
                LocalDateTime coexStart = coexEnd.minusMinutes(splitCHours.multiply(new BigDecimal("60")).longValue());

                LocalDateTime weavingEnd = coexEnd.minusDays(weaveAdvance);
                LocalDateTime weavingStart = weavingEnd.minusMinutes(splitWHours.multiply(new BigDecimal("60")).longValue());

                if (shortfall.compareTo(BigDecimal.ZERO) > 0 && weavingStart.isAfter(coexStart.minusDays(weaveAdvance))) {
                    weavingStart = coexStart.minusDays(weaveAdvance);
                }

                // 根据资源时间线调整排产开始时间
                if (shortfall.compareTo(BigDecimal.ZERO) > 0 && weavingStart.isBefore(machineAvailableTime)) {
                    long shift = ChronoUnit.MINUTES.between(weavingStart, machineAvailableTime);
                    weavingStart = machineAvailableTime;
                    weavingEnd = weavingEnd.plusMinutes(shift);
                    conflictWarnings.add("机台 " + (wm != null ? wm.getMachineId() : "N/A") + " 被占用，织造延至 " + weavingStart);
                }
                if (coexStart.isBefore(lineAvailableTime)) {
                    long shift = ChronoUnit.MINUTES.between(coexStart, lineAvailableTime);
                    coexStart = lineAvailableTime;
                    coexEnd = coexEnd.plusMinutes(shift);
                    conflictWarnings.add("产线 " + (cl != null ? cl.getLineId() : "N/A") + " 被占用，共挤延至 " + coexStart);
                }

                if (weavingStart.isBefore(now) || coexStart.isBefore(now)) {
                    LocalDateTime earliest = weavingStart.isBefore(coexStart) ? weavingStart : coexStart;
                    long shiftMinutes = ChronoUnit.MINUTES.between(earliest, now);
                    weavingStart = weavingStart.plusMinutes(shiftMinutes); weavingEnd = weavingEnd.plusMinutes(shiftMinutes);
                    coexStart = coexStart.plusMinutes(shiftMinutes); coexEnd = coexEnd.plusMinutes(shiftMinutes);
                }

                // 更新资源时间线
                if (wm != null && shortfall.compareTo(BigDecimal.ZERO) > 0) {
                    resourceTimeline.put("W_" + wm.getMachineId(), weavingEnd);
                }
                if (cl != null) {
                    resourceTimeline.put("C_" + cl.getLineId(), coexEnd);
                }

                ScheduleDates wDates = new ScheduleDates(); wDates.startDate = shortfall.compareTo(BigDecimal.ZERO) > 0 ? weavingStart : null; wDates.endDate = shortfall.compareTo(BigDecimal.ZERO) > 0 ? weavingEnd : null; wDates.algoWeavingCapacity = wCap; wDates.algoChangeoverDays = changeoverDays;
                ScheduleDates cDates = new ScheduleDates(); cDates.startDate = coexStart; cDates.endDate = coexEnd; cDates.algoCoexCapacity = cCap; cDates.algoDelayDays = delayDays;

                String orderId = orderIds.stream().filter(id -> orderRepo.findByOrderId(id).stream().anyMatch(o -> o.getFinishedPartNumber().equals(item.getFinishedPartNumber()))).findFirst().orElse(orderIds.get(0));
                Map<String, Object> draftItem = buildDraftView(item.getFinishedPartNumber(), tapePartNumber, proc.getWarpSpec(), proc.getWeftSpec(), proc.getFinishedModelSpec(), proc.getTapeModelSpec(), splitFinished, splitShortfall, wDates, cDates, orderId);
                draftItem.put("plannedMachine", wm != null ? wm.getMachineId() : null);
                draftItem.put("plannedLine", cl != null ? cl.getLineId() : null);
                allResults.add(draftItem);

                if (wDates.startDate != null && wDates.startDate.isBefore(overallStartDate)) overallStartDate = wDates.startDate;
                if (cDates.startDate.isBefore(overallStartDate)) overallStartDate = cDates.startDate;
                if (cDates.endDate.isAfter(overallEndDate)) overallEndDate = cDates.endDate;
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("results", allResults);
        result.put("overallStartDate", overallStartDate != LocalDateTime.MAX ? overallStartDate.toString() : null);
        result.put("overallEndDate", overallEndDate != LocalDateTime.MIN ? overallEndDate.toString() : null);
        result.put("conflictWarnings", conflictWarnings);
        return result;
    }

    public List<Map<String, Object>> getScheduleSummary() {
        List<Object[]> summaries = scheduleRepo.findScheduleSummaryByOrder();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] row : summaries) {
            Map<String, Object> map = new HashMap<>();
            map.put("orderId", row[0]);
            map.put("plannedStartDate", row[1] != null ? row[1].toString() : null);
            map.put("plannedEndDate", row[2] != null ? row[2].toString() : null);
            result.add(map);
        }
        return result;
    }

    public Map<String, Object> getScheduleExecutionStatus(String orderId) {
        List<EstimatedProductionSchedule> schedules = scheduleRepo.findByOrderId(orderId);
        if (schedules.isEmpty()) throw new RuntimeException("该订单无排产记录");

        List<Map<String, Object>> details = new ArrayList<>();
        for (EstimatedProductionSchedule es : schedules) {
            Map<String, Object> detail = new HashMap<>();
            detail.put("finishedPartNumber", es.getFinishedPartNumber());
            detail.put("tapePartNumber", es.getTapePartNumber());
            detail.put("plannedWeavingStart", es.getWeavingStartDate() != null ? es.getWeavingStartDate().toString() : null);
            detail.put("plannedWeavingEnd", es.getWeavingEndDate() != null ? es.getWeavingEndDate().toString() : null);
            detail.put("plannedCoexStart", es.getCoexStartDate() != null ? es.getCoexStartDate().toString() : null);
            detail.put("plannedCoexEnd", es.getCoexEndDate() != null ? es.getCoexEndDate().toString() : null);
            detail.put("plannedMachine", es.getWeavingMachineId());
            detail.put("plannedLine", es.getCoexLineId());

            BigDecimal actualWeavingOutput = BigDecimal.ZERO;
            BigDecimal actualCoexOutput = BigDecimal.ZERO;

            if (es.getTapePartNumber() != null && es.getWeavingStartDate() != null) {
                List<WeavingDailyLog> wLogs = weavingLogRepo.findByTapePartNumber(es.getTapePartNumber());
                actualWeavingOutput = wLogs.stream()
                    .filter(l -> l.getEntryDate() != null && !l.getEntryDate().isBefore(es.getWeavingStartDate().toLocalDate()))
                    .map(l -> l.getCapacityPerDay() != null ? l.getCapacityPerDay() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            }

            if (es.getFinishedPartNumber() != null && es.getCoexStartDate() != null) {
                List<CoexDailyLog> cLogs = coexLogRepo.findByFinishedPartNumber(es.getFinishedPartNumber());
                actualCoexOutput = cLogs.stream()
                    .filter(l -> l.getEntryDate() != null && !l.getEntryDate().isBefore(es.getCoexStartDate().toLocalDate()))
                    .map(l -> l.getCapacityPerDay() != null ? l.getCapacityPerDay() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            }

            detail.put("actualWeavingOutput", actualWeavingOutput);
            detail.put("actualCoexOutput", actualCoexOutput);

            long plannedWeavingDays = es.getWeavingStartDate() != null && es.getWeavingEndDate() != null ?
                ChronoUnit.DAYS.between(es.getWeavingStartDate().toLocalDate(), es.getWeavingEndDate().toLocalDate()) + 1 : 0;
            long plannedCoexDays = es.getCoexStartDate() != null && es.getCoexEndDate() != null ?
                ChronoUnit.DAYS.between(es.getCoexStartDate().toLocalDate(), es.getCoexEndDate().toLocalDate()) + 1 : 0;
            detail.put("plannedWeavingDays", plannedWeavingDays);
            detail.put("plannedCoexDays", plannedCoexDays);

            details.add(detail);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("orderId", orderId);
        result.put("details", details);
        result.put("hasSchedule", !schedules.isEmpty());
        return result;
    }

    private BigDecimal getWeavingAvgCap(String tapePn) {
        List<WeavingDailyLog> logs = weavingLogRepo.findByTapePartNumber(tapePn);
        if (logs == null || logs.isEmpty()) return BigDecimal.ZERO;
        return logs.stream().map(WeavingDailyLog::getCapacityPerDay).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add).divide(new BigDecimal(logs.size()), 4, RoundingMode.HALF_UP).multiply(new BigDecimal("2"));
    }

    private BigDecimal getCoexAvgCap(String finishedPn) {
        List<CoexDailyLog> logs = coexLogRepo.findByFinishedPartNumber(finishedPn);
        if (logs == null || logs.isEmpty()) return BigDecimal.ZERO;
        return logs.stream().map(CoexDailyLog::getCapacityPerDay).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add).divide(new BigDecimal(logs.size()), 4, RoundingMode.HALF_UP);
    }

    private LocalDateTime parseDateTimeSafely(String str) {
        if (str == null || str.trim().isEmpty() || str.equals("null")) return null;
        try { return str.contains("T") ? LocalDateTime.parse(str) : (str.contains(" ") ? LocalDateTime.parse(str.replace(" ", "T")) : LocalDate.parse(str).atStartOfDay()); }
        catch (Exception e) { return LocalDateTime.now(); }
    }
//
    @Transactional
    @SuppressWarnings("unchecked")
    public String commitFinalSchedule(Map<String, Object> finalPayload, String currentUser) {
        String orderId = (String) finalPayload.get("orderId");
        String planId = "PLAN-FINAL-" + System.currentTimeMillis();
        MasterProductionPlan plan = new MasterProductionPlan();
        plan.setPlanId(planId); plan.setOrderId(orderId); plan.setEnteredBy(currentUser); planRepo.save(plan);

        List<Map<String, Object>> details = (List<Map<String, Object>>) finalPayload.get("details");
        for (Map<String, Object> row : details) {
            EstimatedProductionSchedule es = new EstimatedProductionSchedule();
            es.setPlanId(planId); es.setOrderId(orderId);
            es.setFinishedPartNumber((String) row.get("finishedPartNumber")); es.setTapePartNumber((String) row.get("tapePartNumber"));
            if (row.get("weavingStart") != null) { es.setWeavingStartDate(parseDateTimeSafely((String) row.get("weavingStart"))); es.setWeavingEndDate(parseDateTimeSafely((String) row.get("weavingEnd"))); }
            es.setCoexStartDate(parseDateTimeSafely((String) row.get("coexStart"))); es.setCoexEndDate(parseDateTimeSafely((String) row.get("coexEnd")));
            if (row.get("plannedMachine") != null) es.setWeavingMachineId((String) row.get("plannedMachine"));
            if (row.get("plannedLine") != null) es.setCoexLineId((String) row.get("plannedLine"));
            LocalDateTime start = es.getWeavingStartDate() != null ? es.getWeavingStartDate() : es.getCoexStartDate();
            es.setEstimatedTotalDays(new BigDecimal(ChronoUnit.DAYS.between(start.toLocalDate(), es.getCoexEndDate().toLocalDate()) + 1));
            es.setEnteredBy(currentUser); scheduleRepo.save(es);
        }
        return "🎯 高精度排产规划单 " + planId + " 已成功落库下发！";
    }

    private static class ScheduleDates { LocalDateTime startDate; LocalDateTime endDate; BigDecimal algoWeavingCapacity; BigDecimal algoCoexCapacity; BigDecimal algoChangeoverDays; Integer algoDelayDays; }

    private Map<String, Object> buildDraftView(String fPn, String tPn, String warp, String weft, String fSpec, String tSpec, BigDecimal fMeters, BigDecimal tNeed, ScheduleDates w, ScheduleDates c, String orderId) {
        Map<String, Object> m = new HashMap<>(); m.put("orderId", orderId); m.put("finishedPartNumber", fPn); m.put("tapePartNumber", tPn); m.put("warpSpec", warp); m.put("weftSpec", weft); m.put("finishedModelSpec", fSpec); m.put("tapeModelSpec", tSpec); m.put("finishedMeters", fMeters); m.put("tapeMetersNeed", tNeed); m.put("weavingStart", w.startDate != null ? w.startDate.toString() : null); m.put("weavingEnd", w.endDate != null ? w.endDate.toString() : null); m.put("coexStart", c.startDate.toString()); m.put("coexEnd", c.endDate.toString()); m.put("weavingCapacity", w.algoWeavingCapacity); m.put("coexCapacity", c.algoCoexCapacity); m.put("changeoverDays", w.algoChangeoverDays); m.put("startDelay", c.algoDelayDays); return m;
    }
}