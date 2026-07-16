package com.company.scheduling.service;

import com.company.scheduling.domain.*;
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

@Service
public class EstimationService {

    private final MasterProductionPlanRepo planRepo;
    private final EstimatedProductionScheduleRepo scheduleRepo;
    private final ProductionOrderRepo orderRepo;
    private final VirtualWarehouseRepo warehouseRepo;
    private final WeavingDailyLogRepo weavingLogRepo;
    private final CoexDailyLogRepo coexLogRepo;
    private final ProductProcessRepo processRepo;

    public EstimationService(MasterProductionPlanRepo planRepo, EstimatedProductionScheduleRepo scheduleRepo,
                             ProductionOrderRepo orderRepo, VirtualWarehouseRepo warehouseRepo,
                             WeavingDailyLogRepo weavingLogRepo, CoexDailyLogRepo coexLogRepo, ProductProcessRepo processRepo) {
        this.planRepo = planRepo; this.scheduleRepo = scheduleRepo; this.orderRepo = orderRepo;
        this.warehouseRepo = warehouseRepo; this.weavingLogRepo = weavingLogRepo;
        this.coexLogRepo = coexLogRepo; this.processRepo = processRepo;
    }

    public Map<String, Object> previewSchedule(ScheduleAdjustmentRequest req, String currentUser) {
        List<ProductionOrder> orders;
        if (req.getDraftOrders() != null && !req.getDraftOrders().isEmpty()) {
            orders = req.getDraftOrders();
        } else {
            orders = orderRepo.findByOrderId(req.getOrderId());
            if (orders == null || orders.isEmpty()) throw new RuntimeException("查无此订单，请核对订单号！");
        }

        LocalDateTime overallStartDate = LocalDateTime.MAX;
        LocalDateTime overallEndDate = LocalDateTime.MIN;
        List<Map<String, Object>> itemSchedules = new ArrayList<>();

        for (ProductionOrder item : orders) {
            BigDecimal finishedMeters = item.getMetersPerRoll().multiply(new BigDecimal(item.getRollCount()));
            BigDecimal tapeMetersNeeded = finishedMeters.multiply(new BigDecimal("1.10"));

            // ================= 1. 工艺路线熔断拦截 =================
            Optional<ProductProcess> processOpt = processRepo.findByFinishedPartNumber(item.getFinishedPartNumber());
            if (processOpt.isEmpty()) {
                throw new RuntimeException("MISSING_PROCESS:" + item.getFinishedPartNumber());
            }
            ProductProcess proc = processOpt.get();
            String tapePartNumber = proc.getTapePartNumber();

            // ================= 2. 扣除现有虚拟库存 =================
            List<VirtualWarehouse> warehouses = warehouseRepo.findByFinishedPartNumber(item.getFinishedPartNumber());
            BigDecimal currentInventory = warehouses.stream()
                    .map(w -> w.getCurrentStockMeters() != null ? w.getCurrentStockMeters() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal shortfall = tapeMetersNeeded.subtract(currentInventory).max(BigDecimal.ZERO);

            // ================= 3. 智能计算日产能与手工兜底熔断 =================
            ScheduleAdjustmentRequest.ItemAdjustment itemAdj = null;
            if (req.getItemAdjustments() != null) {
                itemAdj = req.getItemAdjustments().stream()
                        .filter(a -> a.getFinishedPartNumber().equals(item.getFinishedPartNumber()))
                        .findFirst().orElse(null);
            }

            BigDecimal wCap = getWeavingAvgCap(tapePartNumber);
            BigDecimal cCap = getCoexAvgCap(item.getFinishedPartNumber());

            BigDecimal changeoverDays = new BigDecimal("1"); // 默认织造改机预留天数
            Integer delayDays = 1; // 默认共挤开机延时天数

            // 优先采纳前端手工干预的值
            if (itemAdj != null) {
                if (itemAdj.getManualWeavingCapacity() != null) wCap = itemAdj.getManualWeavingCapacity();
                if (itemAdj.getManualCoexCapacity() != null) cCap = itemAdj.getManualCoexCapacity();
                if (itemAdj.getManualWeavingChangeoverDays() != null) changeoverDays = itemAdj.getManualWeavingChangeoverDays();
                if (itemAdj.getManualStartDelayDays() != null) delayDays = itemAdj.getManualStartDelayDays();
            }

            // 无历史数据且无手工干预，弹出强制手工录入预估产能
            if (wCap.compareTo(BigDecimal.ZERO) == 0 || cCap.compareTo(BigDecimal.ZERO) == 0) {
                throw new RuntimeException("MISSING_CAPACITY:" + item.getFinishedPartNumber() + ":" + tapePartNumber);
            }

            // ================= 4. 高精度逆向排产核心算法 =================
            // 🌟 此处已修复：将 LocalDateTime.now().plusDays(30).atTime(...) 修改为 LocalDate.now().plusDays(30).atTime(...)
            LocalDateTime deadline = item.getDeliveryDate() != null ?
                    item.getDeliveryDate().atTime(23, 59, 59) :
                    LocalDate.now().plusDays(30).atTime(23, 59, 59);

            ScheduleDates cDates = new ScheduleDates();
            cDates.algoCoexCapacity = cCap;
            cDates.algoDelayDays = delayDays;

            BigDecimal coexHours = finishedMeters.divide(cCap, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("24"));
            cDates.endDate = deadline;
            cDates.startDate = cDates.endDate.minusMinutes(coexHours.multiply(new BigDecimal("60")).longValue());

            ScheduleDates wDates = new ScheduleDates();
            wDates.algoWeavingCapacity = wCap;
            wDates.algoChangeoverDays = changeoverDays;

            if (shortfall.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal weavingHours = shortfall.divide(wCap, 4, RoundingMode.HALF_UP).multiply(new BigDecimal("24"));
                wDates.endDate = cDates.startDate.minusDays(delayDays);
                wDates.startDate = wDates.endDate.minusMinutes(weavingHours.multiply(new BigDecimal("60")).longValue()).minusDays(changeoverDays.intValue());
            }

            LocalDateTime currentItemStart = wDates.startDate != null ? wDates.startDate : cDates.startDate;
            if (currentItemStart.isBefore(overallStartDate)) overallStartDate = currentItemStart;
            if (cDates.endDate.isAfter(overallEndDate)) overallEndDate = cDates.endDate;

            itemSchedules.add(buildDraftView(item.getFinishedPartNumber(), tapePartNumber, proc.getWarpSpec(), proc.getWeftSpec(), proc.getFinishedModelSpec(), proc.getTapeModelSpec(), finishedMeters, shortfall, wDates, cDates, req.getOrderId()));
        }

        Map<String, Object> draft = new HashMap<>();
        draft.put("orderId", req.getOrderId());
        draft.put("overallStartDate", overallStartDate.toString());
        draft.put("overallEndDate", overallEndDate.toString());
        draft.put("totalDays", ChronoUnit.DAYS.between(overallStartDate.toLocalDate(), overallEndDate.toLocalDate()) + 1);
        draft.put("details", itemSchedules);
        return draft;
    }

    private BigDecimal getWeavingAvgCap(String tapePn) {
        List<WeavingDailyLog> logs = weavingLogRepo.findByTapePartNumber(tapePn);
        if (logs == null || logs.isEmpty()) return BigDecimal.ZERO;
        BigDecimal total = logs.stream().map(WeavingDailyLog::getCapacityPerDay).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avgShift = total.divide(new BigDecimal(logs.size()), 4, RoundingMode.HALF_UP);
        return avgShift.multiply(new BigDecimal("2"));
    }

    private BigDecimal getCoexAvgCap(String finishedPn) {
        List<CoexDailyLog> logs = coexLogRepo.findByFinishedPartNumber(finishedPn);
        if (logs == null || logs.isEmpty()) return BigDecimal.ZERO;
        BigDecimal total = logs.stream().map(CoexDailyLog::getCapacityPerDay).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.divide(new BigDecimal(logs.size()), 4, RoundingMode.HALF_UP);
    }

    private LocalDateTime parseDateTimeSafely(String str) {
        if (str == null || str.trim().isEmpty() || str.equals("null")) return null;
        try {
            if (str.contains("T")) {
                return LocalDateTime.parse(str);
            } else if (str.contains(" ")) {
                return LocalDateTime.parse(str.replace(" ", "T"));
            } else {
                return LocalDate.parse(str).atStartOfDay();
            }
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

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

            if (row.get("weavingStart") != null) {
                es.setWeavingStartDate(parseDateTimeSafely((String) row.get("weavingStart")));
                es.setWeavingEndDate(parseDateTimeSafely((String) row.get("weavingEnd")));
            }
            es.setCoexStartDate(parseDateTimeSafely((String) row.get("coexStart")));
            es.setCoexEndDate(parseDateTimeSafely((String) row.get("coexEnd")));

            if (row.get("plannedMachine") != null) es.setWeavingMachineId((String) row.get("plannedMachine"));
            if (row.get("plannedLine") != null) es.setCoexLineId((String) row.get("plannedLine"));

            LocalDateTime start = es.getWeavingStartDate() != null ? es.getWeavingStartDate() : es.getCoexStartDate();
            es.setEstimatedTotalDays(new BigDecimal(ChronoUnit.DAYS.between(start.toLocalDate(), es.getCoexEndDate().toLocalDate()) + 1));
            es.setEnteredBy(currentUser); scheduleRepo.save(es);
        }
        return "🎯 高精度排产规划单 " + planId + " 已成功落库下发！";
    }

    private static class ScheduleDates {
        LocalDateTime startDate;
        LocalDateTime endDate;
        BigDecimal algoWeavingCapacity;
        BigDecimal algoCoexCapacity;
        BigDecimal algoChangeoverDays;
        Integer algoDelayDays;
    }

    private Map<String, Object> buildDraftView(String fPn, String tPn, String warp, String weft, String fSpec, String tSpec, BigDecimal fMeters, BigDecimal tNeed, ScheduleDates w, ScheduleDates c, String orderId) {
        Map<String, Object> m = new HashMap<>();
        m.put("orderId", orderId);
        m.put("finishedPartNumber", fPn); m.put("tapePartNumber", tPn);
        m.put("warpSpec", warp); m.put("weftSpec", weft);
        m.put("finishedModelSpec", fSpec); m.put("tapeModelSpec", tSpec);
        m.put("finishedMeters", fMeters); m.put("tapeMetersNeed", tNeed);

        m.put("weavingStart", w.startDate != null ? w.startDate.toString() : null);
        m.put("weavingEnd", w.endDate != null ? w.endDate.toString() : null);
        m.put("coexStart", c.startDate.toString()); m.put("coexEnd", c.endDate.toString());

        m.put("weavingCapacity", w.algoWeavingCapacity); m.put("coexCapacity", c.algoCoexCapacity);
        m.put("changeoverDays", w.algoChangeoverDays); m.put("startDelay", c.algoDelayDays);
        return m;
    }
}