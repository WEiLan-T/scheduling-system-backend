package com.company.scheduling.service.scheduling;

import com.company.scheduling.domain.EstimatedProductionSchedule;
import com.company.scheduling.domain.MasterProductionPlan;
import com.company.scheduling.repository.EstimatedProductionScheduleRepo;
import com.company.scheduling.repository.MasterProductionPlanRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 排产落库提交器
 */
@Service
public class ScheduleCommitter {

    private static final Logger log = LoggerFactory.getLogger(ScheduleCommitter.class);

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

        List<Map<String, Object>> details = (List<Map<String, Object>>) finalPayload.get("details");
        if (details == null || details.isEmpty()) throw new RuntimeException("排产明细为空，无法落库！");

        // 重复提交幂等化：先清除该订单旧排产记录，避免旧记录残留导致多订单排产时资源时间线重复叠加
        List<EstimatedProductionSchedule> oldSchedules = scheduleRepo.findByOrderId(orderId);
        if (!oldSchedules.isEmpty()) {
            scheduleRepo.deleteAll(oldSchedules);
            scheduleRepo.flush();
            log.info("订单 [{}] 重复提交排产，已清除旧排产记录 {} 条后重新落库", orderId, oldSchedules.size());
        }

        String planId = "PLAN-FINAL-" + System.currentTimeMillis();
        MasterProductionPlan plan = new MasterProductionPlan();
        plan.setPlanId(planId); plan.setOrderId(orderId); plan.setEnteredBy(currentUser); planRepo.save(plan);

        // 库存整根消耗透传：汇总各明细的 consumedTapeCodes 与 surplusMeters（Map弱类型仅加key，不改表结构）
        List<Map<String, Object>> allConsumedTapeCodes = new ArrayList<>();
        BigDecimal totalSurplusMeters = BigDecimal.ZERO;

        for (Map<String, Object> row : details) {
            Object consumedObj = row.get("consumedTapeCodes");
            if (consumedObj instanceof List) {
                for (Object entry : (List<?>) consumedObj) {
                    if (entry instanceof Map) allConsumedTapeCodes.add((Map<String, Object>) entry);
                }
            }
            Object surplusObj = row.get("surplusMeters");
            if (surplusObj instanceof BigDecimal) {
                totalSurplusMeters = totalSurplusMeters.add((BigDecimal) surplusObj);
            } else if (surplusObj instanceof Number) {
                totalSurplusMeters = totalSurplusMeters.add(new BigDecimal(surplusObj.toString()));
            }

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
            es.setEnteredBy(currentUser);

            // 日期合理性校验：织造/共挤结束不早于开始（容错：仅记录 warning，不阻断落库）
            if (es.getWeavingStartDate() != null && es.getWeavingEndDate() != null
                    && es.getWeavingEndDate().isBefore(es.getWeavingStartDate())) {
                log.warn("排产日期异常：成品 [{}] 织造结束 {} 早于织造开始 {}",
                        es.getFinishedPartNumber(), es.getWeavingEndDate(), es.getWeavingStartDate());
            }
            if (es.getCoexStartDate() != null && es.getCoexEndDate() != null
                    && es.getCoexEndDate().isBefore(es.getCoexStartDate())) {
                log.warn("排产日期异常：成品 [{}] 共挤结束 {} 早于共挤开始 {}",
                        es.getFinishedPartNumber(), es.getCoexEndDate(), es.getCoexStartDate());
            }

            scheduleRepo.save(es);
        }

        StringBuilder message = new StringBuilder("🎯 高精度排产规划单 " + planId + " 已成功落库下发！");
        if (!allConsumedTapeCodes.isEmpty()) {
            BigDecimal consumedTotal = allConsumedTapeCodes.stream()
                    .map(c -> c.get("meters"))
                    .filter(Objects::nonNull)
                    .map(m -> m instanceof BigDecimal ? (BigDecimal) m
                            : m instanceof Number ? new BigDecimal(m.toString()) : null)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            message.append("（消耗库存整根 ").append(allConsumedTapeCodes.size()).append(" 卷 / ")
                    .append(consumedTotal.stripTrailingZeros().toPlainString()).append(" 米，直接投入共挤");
            if (totalSurplusMeters.compareTo(BigDecimal.ZERO) > 0) {
                message.append("，最后一根整根投入超额 ").append(totalSurplusMeters.stripTrailingZeros().toPlainString()).append(" 米（未截断，已明示标注）");
            }
            message.append("）");
        }
        return message.toString();
    }

    private LocalDateTime parseDateTimeSafely(String str) {
        if (str == null || str.trim().isEmpty() || str.equals("null")) return null;
        try { return str.contains("T") ? LocalDateTime.parse(str) : (str.contains(" ") ? LocalDateTime.parse(str.replace(" ", "T")) : LocalDate.parse(str).atStartOfDay()); }
        catch (Exception e) { return LocalDateTime.now(); }
    }
}
