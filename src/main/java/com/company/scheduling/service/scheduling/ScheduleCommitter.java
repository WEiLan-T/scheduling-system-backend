package com.company.scheduling.service.scheduling;

import com.company.scheduling.domain.EstimatedProductionSchedule;
import com.company.scheduling.domain.MasterProductionPlan;
import com.company.scheduling.repository.EstimatedProductionScheduleRepo;
import com.company.scheduling.repository.MasterProductionPlanRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * 排产落库提交器
 */
@Service
public class ScheduleCommitter {

    private final MasterProductionPlanRepo planRepo;
    private final EstimatedProductionScheduleRepo scheduleRepo;

    public ScheduleCommitter(MasterProductionPlanRepo planRepo, EstimatedProductionScheduleRepo scheduleRepo) {
        this.planRepo = planRepo;
        this.scheduleRepo = scheduleRepo;
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public String commitFinalSchedule(Map<String, Object> finalPayload, String currentUser) {
        String orderId = (String) finalPayload.get("orderId");
        if (orderId == null || orderId.trim().isEmpty()) throw new RuntimeException("排产单缺少订单号，无法落库！");

        String planId = "PLAN-FINAL-" + System.currentTimeMillis();
        MasterProductionPlan plan = new MasterProductionPlan();
        plan.setPlanId(planId); plan.setOrderId(orderId); plan.setEnteredBy(currentUser); planRepo.save(plan);

        List<Map<String, Object>> details = (List<Map<String, Object>>) finalPayload.get("details");
        if (details == null || details.isEmpty()) throw new RuntimeException("排产明细为空，无法落库！");

        for (Map<String, Object> row : details) {
            EstimatedProductionSchedule es = new EstimatedProductionSchedule();
            es.setPlanId(planId); es.setOrderId(orderId);
            es.setFinishedPartNumber((String) row.get("finishedPartNumber")); es.setTapePartNumber((String) row.get("tapePartNumber"));
            if (row.get("weavingStart") != null) { es.setWeavingStartDate(parseDateTimeSafely((String) row.get("weavingStart"))); es.setWeavingEndDate(parseDateTimeSafely((String) row.get("weavingEnd"))); }
            es.setCoexStartDate(parseDateTimeSafely((String) row.get("coexStart"))); es.setCoexEndDate(parseDateTimeSafely((String) row.get("coexEnd")));
            if (row.get("plannedMachine") != null) es.setWeavingMachineId((String) row.get("plannedMachine"));
            if (row.get("plannedLine") != null) es.setCoexLineId((String) row.get("plannedLine"));

            // 安全计算总天数：防止 start 或 coexEndDate 为 null 导致 NPE
            LocalDateTime start = es.getWeavingStartDate() != null ? es.getWeavingStartDate() : es.getCoexStartDate();
            LocalDateTime end = es.getCoexEndDate() != null ? es.getCoexEndDate() : es.getWeavingEndDate();
            if (start != null && end != null) {
                es.setEstimatedTotalDays(new BigDecimal(ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate()) + 1));
            } else {
                es.setEstimatedTotalDays(BigDecimal.ZERO);
            }
            es.setEnteredBy(currentUser); scheduleRepo.save(es);
        }
        return "🎯 高精度排产规划单 " + planId + " 已成功落库下发！";
    }

    private LocalDateTime parseDateTimeSafely(String str) {
        if (str == null || str.trim().isEmpty() || str.equals("null")) return null;
        try { return str.contains("T") ? LocalDateTime.parse(str) : (str.contains(" ") ? LocalDateTime.parse(str.replace(" ", "T")) : LocalDate.parse(str).atStartOfDay()); }
        catch (Exception e) { return LocalDateTime.now(); }
    }
}
