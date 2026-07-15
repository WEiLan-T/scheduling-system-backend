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
    private final VirtualWarehouseRepo warehouseRepo;
    private final WeavingDailyLogRepo weavingLogRepo;
    private final ProductProcessRepo processRepo;

    public EstimationService(MasterProductionPlanRepo planRepo, EstimatedProductionScheduleRepo scheduleRepo,
                             ProductionOrderRepo orderRepo, VirtualWarehouseRepo warehouseRepo,
                             WeavingDailyLogRepo weavingLogRepo, ProductProcessRepo processRepo) { // 👈 构造函数追加
        this.planRepo = planRepo; this.scheduleRepo = scheduleRepo; this.orderRepo = orderRepo;
        this.warehouseRepo = warehouseRepo; this.weavingLogRepo = weavingLogRepo; this.processRepo = processRepo;
    }

    private static class CapacityLedger { LocalDate nextAvailableWeavingDate; LocalDate nextAvailableCoexDate; }

    // ==========================================
    // 🌟 阶段一：带【排队账本】和【离线模拟】的纯草稿推演
    // ==========================================
    public Map<String, Object> previewSchedule(ScheduleAdjustmentRequest req, String currentUser) {
        List<ProductionOrder> orders;
        if (req.getDraftOrders() != null && !req.getDraftOrders().isEmpty()) {
            orders = req.getDraftOrders();
        } else {
            orders = orderRepo.findByOrderId(req.getOrderId());
            if (orders == null || orders.isEmpty()) throw new RuntimeException("查无此订单，请核对订单号！");
        }

        CapacityLedger ledger = new CapacityLedger();
        LocalDate dbMaxWeaving = scheduleRepo.findMaxWeavingEndDate();
        ledger.nextAvailableWeavingDate = (dbMaxWeaving != null && dbMaxWeaving.isAfter(LocalDate.now())) ? dbMaxWeaving : LocalDate.now();

        LocalDate dbMaxCoex = scheduleRepo.findMaxCoexEndDate();
        ledger.nextAvailableCoexDate = (dbMaxCoex != null && dbMaxCoex.isAfter(LocalDate.now())) ? dbMaxCoex : LocalDate.now();

        LocalDate overallStartDate = LocalDate.MAX;
        LocalDate overallEndDate = LocalDate.MIN;
        List<Map<String, Object>> itemSchedules = new ArrayList<>();

        for (ProductionOrder item : orders) {
            BigDecimal finishedMeters = item.getMetersPerRoll().multiply(new BigDecimal(item.getRollCount()));
            BigDecimal tapeMetersNeeded = finishedMeters.multiply(new BigDecimal("1.10"));

            // ========================================================
            // 🌟 1. 初始化默认值
            // ========================================================
            String tapePartNumber = item.getFinishedPartNumber();
            String warpSpec = "-";
            String weftSpec = "-";
            String finishedModelSpec = "-";
            String tapeModelSpec = "-";

            // ========================================================
            // 🌟 2. 从【工艺表 ProductProcess】中提取真正的规格 (绝对不能用 wh 去 get)
            // ========================================================
            Optional<ProductProcess> processOpt = processRepo.findByFinishedPartNumber(item.getFinishedPartNumber());
            if (processOpt.isPresent()) {
                ProductProcess proc = processOpt.get();
                tapePartNumber = proc.getTapePartNumber();
                warpSpec = proc.getWarpSpec();
                weftSpec = proc.getWeftSpec();

                // 👇 核心修正：从 proc (工艺对象) 里提取规格，赋值给局部变量
                finishedModelSpec = proc.getFinishedModelSpec();
                tapeModelSpec = proc.getTapeModelSpec();
            }

            // ========================================================
            // 🌟 3. 库存推演部分 (保持不变，计算 currentInventory 等)
            // ========================================================
            List<VirtualWarehouse> warehouses = warehouseRepo.findByFinishedPartNumber(item.getFinishedPartNumber());
            BigDecimal currentInventory = BigDecimal.ZERO;
            if (!warehouses.isEmpty()) {
                currentInventory = warehouses.stream()
                        .map(w -> w.getCurrentStockMeters() != null ? w.getCurrentStockMeters() : BigDecimal.ZERO)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
            }

            BigDecimal shortfall = tapeMetersNeeded.subtract(currentInventory).max(BigDecimal.ZERO);

            ScheduleAdjustmentRequest.ItemAdjustment itemAdj = null;
            if (req.getItemAdjustments() != null) {
                itemAdj = req.getItemAdjustments().stream().filter(a -> a.getFinishedPartNumber().equals(item.getFinishedPartNumber())).findFirst().orElse(null);
            }

            ScheduleDates wDates = weavingSchedulingAlgorithm(tapePartNumber, shortfall, itemAdj, ledger);
            ScheduleDates cDates = coextrusionSchedulingAlgorithm(targetQtyToCoexAlgorithm(finishedMeters), tapeMetersNeeded, currentInventory, wDates, itemAdj, ledger);

            LocalDate currentItemStart = wDates.startDate != null ? wDates.startDate : cDates.startDate;
            if (currentItemStart.isBefore(overallStartDate)) overallStartDate = currentItemStart;
            if (cDates.endDate.isAfter(overallEndDate)) overallEndDate = cDates.endDate;

            // ========================================================
            // 🌟 4. 构建草稿视图 (直接传入上面定义好的局部变量！)
            // ========================================================
            itemSchedules.add(buildDraftView(
                    item.getFinishedPartNumber(),
                    tapePartNumber,
                    warpSpec,
                    weftSpec,
                    finishedModelSpec, // 👈 直接传局部变量，不要写 wh.get...
                    tapeModelSpec,     // 👈 直接传局部变量，不要写 wh.get...
                    wDates,
                    cDates
            ));
        }

        Map<String, Object> draft = new HashMap<>();
        draft.put("orderId", req.getOrderId());
        draft.put("overallStartDate", overallStartDate.toString());
        draft.put("overallEndDate", overallEndDate.toString());
        draft.put("totalDays", ChronoUnit.DAYS.between(overallStartDate, overallEndDate) + 1);
        draft.put("details", itemSchedules);

        return draft;
    }

    private BigDecimal targetQtyToCoexAlgorithm(BigDecimal targetQty) {
        return targetQty;
    }

    // ==========================================
    // 🌟 阶段二：人类盖章确认 (固化落库)
    // ==========================================
    @Transactional
    @SuppressWarnings("unchecked")
    public String commitFinalSchedule(Map<String, Object> finalPayload, String currentUser) {
        String orderId = (String) finalPayload.get("orderId");
        String planId = "PLAN-FINAL-" + System.currentTimeMillis();

        MasterProductionPlan plan = new MasterProductionPlan();
        plan.setPlanId(planId); plan.setOrderId(orderId); plan.setEnteredBy(currentUser);
        planRepo.save(plan);

        List<Map<String, Object>> details = (List<Map<String, Object>>) finalPayload.get("details");
        for (Map<String, Object> row : details) {
            EstimatedProductionSchedule es = new EstimatedProductionSchedule();
            es.setPlanId(planId); es.setOrderId(orderId);
            es.setFinishedPartNumber((String) row.get("finishedPartNumber"));
            es.setTapePartNumber((String) row.get("tapePartNumber"));

            if (row.get("weavingStart") != null && !row.get("weavingStart").toString().trim().isEmpty()) {
                es.setWeavingStartDate(LocalDate.parse((String) row.get("weavingStart")));
                es.setWeavingEndDate(LocalDate.parse((String) row.get("weavingEnd")));
            }
            es.setCoexStartDate(LocalDate.parse((String) row.get("coexStart")));
            es.setCoexEndDate(LocalDate.parse((String) row.get("coexEnd")));

            // 👇 新增：将前端草稿中选择的机台与产线，持久化落库
            if (row.get("plannedMachine") != null) {
                es.setWeavingMachineId((String) row.get("plannedMachine"));
            }
            if (row.get("plannedLine") != null) {
                es.setCoexLineId((String) row.get("plannedLine"));
            }

            LocalDate start = es.getWeavingStartDate() != null ? es.getWeavingStartDate() : es.getCoexStartDate();
            es.setEstimatedTotalDays(new BigDecimal(ChronoUnit.DAYS.between(start, es.getCoexEndDate()) + 1));
            es.setEnteredBy(currentUser);
            scheduleRepo.save(es);
        }
        return "🎯 经计划员复核，排产规划单 " + planId + " 已成功落库下发！";
    }

    // ==========================================
    // 🧶 织造底层算法：排队避让 + 改机
    // ==========================================
    private ScheduleDates weavingSchedulingAlgorithm(String tapePartNumber, BigDecimal targetQty, ScheduleAdjustmentRequest.ItemAdjustment adj, CapacityLedger ledger) {
        ScheduleDates dates = new ScheduleDates(); dates.workshopId = "织造1车间";
        if (targetQty.compareTo(BigDecimal.ZERO) == 0) return dates;

        BigDecimal standardDailyCapacity = new BigDecimal("250");
        Optional<WeavingDailyLog> latestLog = weavingLogRepo.findFirstByTapePartNumberOrderByEntryDateDesc(tapePartNumber);
        if (latestLog.isPresent() && latestLog.get().getCapacityPerDay() != null) {
            standardDailyCapacity = latestLog.get().getCapacityPerDay();
        }

        BigDecimal changeoverDays = (adj != null && adj.getManualWeavingChangeoverDays() != null)
                ? adj.getManualWeavingChangeoverDays() : new BigDecimal("1");
        dates.algoChangeoverDays = changeoverDays;

        int productionDays = targetQty.divide(standardDailyCapacity, RoundingMode.CEILING).intValue();

        // 🌟 排队机制：在【织造账本现存极值】的基础上往后排
        dates.startDate = ledger.nextAvailableWeavingDate.plusDays(changeoverDays.intValue() + 1);
        dates.endDate = dates.startDate.plusDays(productionDays > 0 ? productionDays - 1 : 0);

        // 更新织造账本游标，供下一个订单排队
        ledger.nextAvailableWeavingDate = dates.endDate;

        return dates;
    }

    // ==========================================
    // 🗜️ 共挤底层算法：防停机倒推 + 账本排队
    // ==========================================
    private ScheduleDates coextrusionSchedulingAlgorithm(BigDecimal targetQty, BigDecimal tapeMetersNeeded, BigDecimal currentInventory, ScheduleDates wDates, ScheduleAdjustmentRequest.ItemAdjustment adj, CapacityLedger ledger) {
        ScheduleDates dates = new ScheduleDates();
        dates.workshopId = wDates.workshopId != null && wDates.workshopId.contains("织造") ? wDates.workshopId.replace("织造", "共挤") : "共挤1车间";

        BigDecimal dailyCapacity = (adj != null && adj.getManualCoexCapacity() != null) ? adj.getManualCoexCapacity() : new BigDecimal("400");
        int delay = (adj != null && adj.getManualStartDelayDays() != null) ? adj.getManualStartDelayDays() : 1;
        dates.algoCoexCapacity = dailyCapacity; dates.algoDelayDays = delay;

        int coexDays = targetQty.divide(dailyCapacity, RoundingMode.CEILING).intValue();
        coexDays = coexDays > 0 ? coexDays - 1 : 0;

        if (currentInventory.compareTo(tapeMetersNeeded) >= 0) {
            // 纯库存直开：排在【共挤账本极值】之后
            dates.startDate = ledger.nextAvailableCoexDate.plusDays(1);
            dates.endDate = dates.startDate.plusDays(coexDays);
        } else {
            if (wDates.endDate != null) {
                // 算法碰撞验证
                LocalDate idealEndDate = wDates.endDate.plusDays(delay);
                LocalDate idealStartDate = idealEndDate.minusDays(coexDays);

                // 🌟 碰撞墙 1：不能早于共挤车间现有的排队极值 (机器被占)
                if (idealStartDate.isBefore(ledger.nextAvailableCoexDate)) {
                    idealStartDate = ledger.nextAvailableCoexDate.plusDays(1);
                    idealEndDate = idealStartDate.plusDays(coexDays);
                }
                // 🌟 碰撞墙 2：不能早于织造开工时间 (带坯断料)
                if (idealStartDate.isBefore(wDates.startDate)) {
                    idealStartDate = wDates.startDate;
                    idealEndDate = idealStartDate.plusDays(coexDays);
                }

                dates.startDate = idealStartDate;
                dates.endDate = idealEndDate;
            } else {
                dates.startDate = ledger.nextAvailableCoexDate.plusDays(1);
                dates.endDate = dates.startDate.plusDays(coexDays);
            }
        }

        // 更新共挤账本游标，供下一个订单排队
        ledger.nextAvailableCoexDate = dates.endDate;

        return dates;
    }

    private static class ScheduleDates {
        LocalDate startDate; LocalDate endDate; String workshopId;
        BigDecimal algoChangeoverDays; BigDecimal algoCoexCapacity; Integer algoDelayDays;
    }

    private Map<String, Object> buildDraftView(String fPn, String tPn, String warp, String weft, String fSpec, String tSpec, ScheduleDates w, ScheduleDates c) {
        Map<String, Object> m = new HashMap<>();
        m.put("finishedPartNumber", fPn);
        m.put("tapePartNumber", tPn);
        m.put("warpSpec", warp);
        m.put("weftSpec", weft);
        m.put("finishedModelSpec", fSpec); // 👈 传给前端大盘
        m.put("tapeModelSpec", tSpec);     // 👈 传给前端大盘
        m.put("weavingStart", w.startDate != null ? w.startDate.toString() : null);
        m.put("weavingEnd", w.endDate != null ? w.endDate.toString() : null);
        m.put("coexStart", c.startDate.toString()); m.put("coexEnd", c.endDate.toString());
        m.put("weavingWorkshop", w.startDate != null ? w.workshopId : "无需织造");
        m.put("coexWorkshop", c.workshopId);
        m.put("changeoverDays", w.algoChangeoverDays); m.put("coexCapacity", c.algoCoexCapacity); m.put("startDelay", c.algoDelayDays);
        return m;
    }
}