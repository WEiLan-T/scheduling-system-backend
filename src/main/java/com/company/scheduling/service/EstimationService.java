package com.company.scheduling.service;

import com.company.scheduling.domain.*;
import com.company.scheduling.dto.ScheduleAdjustmentRequest;
import com.company.scheduling.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class EstimationService {

    private final MasterProductionPlanRepo planRepo;
    private final EstimatedProductionScheduleRepo scheduleRepo;
    private final ProductionOrderRepo orderRepo;
    private final VirtualWarehouseRepo warehouseRepo; // 🌟 引入虚拟库存依赖
    private final WeavingDailyLogRepo weavingLogRepo;

    public EstimationService(MasterProductionPlanRepo planRepo,
                             EstimatedProductionScheduleRepo scheduleRepo,
                             ProductionOrderRepo orderRepo,
                             VirtualWarehouseRepo warehouseRepo,
                             WeavingDailyLogRepo weavingLogRepo) { // 🌟 修改构造函数
        this.planRepo = planRepo;
        this.scheduleRepo = scheduleRepo;
        this.orderRepo = orderRepo;
        this.warehouseRepo = warehouseRepo;
        this.weavingLogRepo = weavingLogRepo;
    }

    @Transactional
    public Map<String, Object> calculateAdvancedSchedule(ScheduleAdjustmentRequest req, String currentUser) {
        List<ProductionOrder> orders = orderRepo.findByOrderId(req.getOrderId());
        if (orders == null || orders.isEmpty()) {
            throw new RuntimeException("查无此订单，请核对订单号！");
        }

        LocalDate overallStartDate = LocalDate.MAX;
        LocalDate overallEndDate = LocalDate.MIN;
        List<Map<String, Object>> itemSchedules = new ArrayList<>();
        String planId = "PLAN-V4-" + System.currentTimeMillis();

        MasterProductionPlan plan = new MasterProductionPlan();
        plan.setPlanId(planId);
        plan.setOrderId(req.getOrderId());
        plan.setEnteredBy(currentUser);
        planRepo.save(plan);

        for (ProductionOrder item : orders) {
            BigDecimal finishedMeters = item.getMetersPerRoll().multiply(new BigDecimal(item.getRollCount()));

            // 细则4：10% 冗余带坯损耗
            BigDecimal tapeMetersNeeded = finishedMeters.multiply(new BigDecimal("1.10"));
            String tapePartNumber = item.getFinishedPartNumber() + "-TP";

            // 🌟 细则1：从底层数据库真实读取虚拟库存
            VirtualWarehouse wh = warehouseRepo.findByTapePartNumber(tapePartNumber).orElse(null);
            BigDecimal currentInventory = wh != null && wh.getCurrentStockMeters() != null ? wh.getCurrentStockMeters() : BigDecimal.ZERO;

            // 计算缺口
            BigDecimal shortfall = tapeMetersNeeded.subtract(currentInventory).max(BigDecimal.ZERO);

            // 🧶 织造算法：只排产缺口部分
            ScheduleDates weavingDates = weavingSchedulingAlgorithm(tapePartNumber, shortfall, req);

            // 🗜️ 共挤算法：依据库存满足度与车间映射进行排产
            ScheduleDates coexDates = coextrusionSchedulingAlgorithm(item.getFinishedPartNumber(), finishedMeters, tapeMetersNeeded, currentInventory, weavingDates, req);

            LocalDate currentItemStart = weavingDates.startDate != null ? weavingDates.startDate : coexDates.startDate;
            if (currentItemStart.isBefore(overallStartDate)) overallStartDate = currentItemStart;
            if (coexDates.endDate.isAfter(overallEndDate)) overallEndDate = coexDates.endDate;

            saveScheduleRecord(planId, req.getOrderId(), item.getFinishedPartNumber(), tapePartNumber, weavingDates, coexDates, currentItemStart, currentUser);
            itemSchedules.add(buildItemView(item.getFinishedPartNumber(), weavingDates, coexDates));
        }

        long totalDays = ChronoUnit.DAYS.between(overallStartDate, overallEndDate) + 1;

        Map<String, Object> result = new HashMap<>();
        result.put("orderId", req.getOrderId());
        result.put("overallStartDate", overallStartDate.toString());
        result.put("overallEndDate", overallEndDate.toString());
        result.put("totalDays", totalDays);
        result.put("details", itemSchedules);

        return result;
    }

    // ==========================================
    // 🧶 织造排产算法 (保留改机与人员参数)
    // ==========================================
    private ScheduleDates weavingSchedulingAlgorithm(String tapePartNumber, BigDecimal targetQty, ScheduleAdjustmentRequest req) {
        ScheduleDates dates = new ScheduleDates();
        dates.workshopId = "织造1车间";

        if (targetQty.compareTo(BigDecimal.ZERO) == 0) return dates;

        String currentTapeOnMachine = "8D1001";

        // 🌟 核心升级：调取生产该带坯的机台的真实日产能
        BigDecimal standardDailyCapacity = new BigDecimal("250"); // 兜底保底产能
        Optional<WeavingDailyLog> latestLog = weavingLogRepo.findFirstByTapePartNumberOrderByEntryDateDesc(tapePartNumber);
        if (latestLog.isPresent() && latestLog.get().getCapacityPerDay() != null && latestLog.get().getCapacityPerDay().compareTo(BigDecimal.ZERO) > 0) {
            standardDailyCapacity = latestLog.get().getCapacityPerDay(); // 提取真实历史数据！
        }

        BigDecimal changeoverDays = req.getManualWeavingChangeoverDays() != null
                ? req.getManualWeavingChangeoverDays()
                : (currentTapeOnMachine.charAt(0) != tapePartNumber.charAt(0) ? new BigDecimal("2") : new BigDecimal("1"));

        // 用真实的产能来计算所需天数
        int productionDays = targetQty.divide(standardDailyCapacity, RoundingMode.CEILING).intValue();

        dates.startDate = LocalDate.now().plusDays(changeoverDays.intValue() + 1);
        dates.endDate = dates.startDate.plusDays(productionDays > 0 ? productionDays - 1 : 0);

        return dates;
    }

    // ==========================================
    // 🗜️ 共挤排产算法 (库存条件分支 + 车间软映射)
    // ==========================================
    private ScheduleDates coextrusionSchedulingAlgorithm(
            String finishedPartNumber,
            BigDecimal targetQty,
            BigDecimal tapeMetersNeeded,
            BigDecimal currentInventory,
            ScheduleDates weavingDates,
            ScheduleAdjustmentRequest req) {

        ScheduleDates dates = new ScheduleDates();

        // 🌟 细则2：车间软映射 (如 "织造1车间" 自动映射给 "共挤1车间")
        String targetWorkshop = "共挤1车间"; // 默认兜底
        if (weavingDates.workshopId != null && weavingDates.workshopId.contains("织造")) {
            targetWorkshop = weavingDates.workshopId.replace("织造", "共挤");
        }
        dates.workshopId = targetWorkshop;

        BigDecimal dailyCapacity = req.getManualCoexCapacity() != null ? req.getManualCoexCapacity() : new BigDecimal("400");
        int coexDays = targetQty.divide(dailyCapacity, RoundingMode.CEILING).intValue();
        coexDays = coexDays > 0 ? coexDays - 1 : 0;

        // 🌟 细则1：库存条件分支
        if (currentInventory.compareTo(tapeMetersNeeded) >= 0) {
            // 分支 A：虚拟库存满足开工条件，无视织造，明日直接安排生产
            dates.startDate = LocalDate.now().plusDays(1);
            dates.endDate = dates.startDate.plusDays(coexDays);
        } else {
            // 分支 B：虚拟库存不足，查询织造“预计满足时间 (weavingDates.endDate)”，倒推防停机开工
            if (weavingDates.endDate != null) {
                int delay = req.getManualStartDelayDays() != null ? req.getManualStartDelayDays() : 1;

                dates.endDate = weavingDates.endDate.plusDays(delay);
                dates.startDate = dates.endDate.minusDays(coexDays);

                // 安全兜底：共挤不可能早于织造开工
                if (dates.startDate.isBefore(weavingDates.startDate)) {
                    dates.startDate = weavingDates.startDate;
                    dates.endDate = dates.startDate.plusDays(coexDays);
                }
            } else {
                // 异常兜底，强制明日开工
                dates.startDate = LocalDate.now().plusDays(1);
                dates.endDate = dates.startDate.plusDays(coexDays);
            }
        }
        return dates;
    }

    // --- 内部数据结构与组装工具 ---
    private static class ScheduleDates {
        LocalDate startDate;
        LocalDate endDate;
        String workshopId; // 新增车间记录
    }

    private void saveScheduleRecord(String pId, String oId, String fPn, String tPn, ScheduleDates w, ScheduleDates c, LocalDate s, String u) {
        EstimatedProductionSchedule es = new EstimatedProductionSchedule();
        es.setPlanId(pId); es.setOrderId(oId); es.setFinishedPartNumber(fPn); es.setTapePartNumber(tPn);
        es.setWeavingStartDate(w.startDate); es.setWeavingEndDate(w.endDate);
        es.setCoexStartDate(c.startDate); es.setCoexEndDate(c.endDate);
        es.setEstimatedTotalDays(new BigDecimal(ChronoUnit.DAYS.between(s, c.endDate) + 1));
        es.setEnteredBy(u);
        scheduleRepo.save(es);
    }

    private Map<String, Object> buildItemView(String fPn, ScheduleDates w, ScheduleDates c) {
        Map<String, Object> m = new HashMap<>();
        m.put("finishedPartNumber", fPn);
        m.put("weavingStart", w.startDate != null ? w.startDate.toString() : null);
        m.put("weavingEnd", w.endDate != null ? w.endDate.toString() : null);
        m.put("coexStart", c.startDate.toString());
        m.put("coexEnd", c.endDate.toString());

        // 传递给前端展示分配的车间
        m.put("weavingWorkshop", w.startDate != null ? w.workshopId : "无需织造");
        m.put("coexWorkshop", c.workshopId);
        return m;
    }
}