package com.company.scheduling.service;

import com.company.scheduling.domain.*;
import com.company.scheduling.dto.InquiryRequest;
import com.company.scheduling.dto.MultiOrderScheduleRequest;
import com.company.scheduling.dto.ScheduleAdjustmentRequest;
import com.company.scheduling.repository.*;
import com.company.scheduling.service.scheduling.InquiryCalculator;
import com.company.scheduling.service.scheduling.CapacityMatcher;
import com.company.scheduling.service.scheduling.ScheduleCommitter;
import com.company.scheduling.service.scheduling.SchedulingEngine;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 排产服务协调层（Facade）
 * 委托具体业务给 SchedulingEngine 和 ScheduleCommitter
 * 保留查询类方法（getScheduleSummary / getScheduleExecutionStatus）
 */
@Service
public class EstimationService {

    private final SchedulingEngine schedulingEngine;
    private final ScheduleCommitter scheduleCommitter;
    private final InquiryCalculator inquiryCalculator;
    private final EstimatedProductionScheduleRepo scheduleRepo;
    private final WeavingDailyLogRepo weavingLogRepo;
    private final CoexDailyLogRepo coexLogRepo;
    private final CapacityMatcher capacityMatcher;
    private final ProductProcessRepo processRepo;
    private final WeavingMachineStatusRepo weavingStatusRepo;
    private final CoexLineStatusRepo coexStatusRepo;

    public EstimationService(SchedulingEngine schedulingEngine,
                             ScheduleCommitter scheduleCommitter,
                             InquiryCalculator inquiryCalculator,
                             EstimatedProductionScheduleRepo scheduleRepo,
                             WeavingDailyLogRepo weavingLogRepo,
                             CoexDailyLogRepo coexLogRepo,
                             CapacityMatcher capacityMatcher,
                             ProductProcessRepo processRepo,
                             WeavingMachineStatusRepo weavingStatusRepo,
                             CoexLineStatusRepo coexStatusRepo) {
        this.schedulingEngine = schedulingEngine;
        this.scheduleCommitter = scheduleCommitter;
        this.inquiryCalculator = inquiryCalculator;
        this.scheduleRepo = scheduleRepo;
        this.weavingLogRepo = weavingLogRepo;
        this.coexLogRepo = coexLogRepo;
        this.capacityMatcher = capacityMatcher;
        this.processRepo = processRepo;
        this.weavingStatusRepo = weavingStatusRepo;
        this.coexStatusRepo = coexStatusRepo;
    }

    public Map<String, Object> previewSchedule(ScheduleAdjustmentRequest req, String currentUser) {
        return schedulingEngine.previewSingleOrder(req, currentUser);
    }

    public Map<String, Object> previewMultiOrderSchedule(MultiOrderScheduleRequest req, String currentUser) {
        return schedulingEngine.previewMultiOrder(req, currentUser);
    }

    public Map<String, Object> calculateInquiry(InquiryRequest request, String currentUser) {
        return inquiryCalculator.calculateInquiry(request, currentUser);
    }

    public String commitFinalSchedule(Map<String, Object> finalPayload, String currentUser) {
        return scheduleCommitter.commitFinalSchedule(finalPayload, currentUser);
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

        // 🌟 性能优化：预加载所有日志并按零件号分组，避免循环内逐条查询
        Set<String> neededTapePns = schedules.stream()
                .map(EstimatedProductionSchedule::getTapePartNumber)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<String> neededFinishedPns = schedules.stream()
                .map(EstimatedProductionSchedule::getFinishedPartNumber)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<String, List<WeavingDailyLog>> weavingLogsByTapePn = weavingLogRepo.findAll().stream()
                .filter(l -> l.getPartNumber() != null && neededTapePns.contains(l.getPartNumber()))
                .collect(Collectors.groupingBy(WeavingDailyLog::getPartNumber));
        Map<String, List<CoexDailyLog>> coexLogsByFinishedPn = coexLogRepo.findAll().stream()
                .filter(l -> l.getProductModel() != null && neededFinishedPns.contains(l.getProductModel()))
                .collect(Collectors.groupingBy(CoexDailyLog::getProductModel));

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
                List<WeavingDailyLog> wLogs = weavingLogsByTapePn.getOrDefault(es.getTapePartNumber(), Collections.emptyList());
                actualWeavingOutput = wLogs.stream()
                    .filter(l -> l.getEntryDate() != null && !l.getEntryDate().isBefore(es.getWeavingStartDate().toLocalDate()))
                    .map(l -> l.getShiftOutput() != null ? l.getShiftOutput() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            }

            if (es.getFinishedPartNumber() != null && es.getCoexStartDate() != null) {
                List<CoexDailyLog> cLogs = coexLogsByFinishedPn.getOrDefault(es.getFinishedPartNumber(), Collections.emptyList());
                actualCoexOutput = cLogs.stream()
                    .filter(l -> l.getLogDate() != null && !l.getLogDate().isBefore(es.getCoexStartDate().toLocalDate()))
                    .map(l -> l.getCapacityMeters() != null ? l.getCapacityMeters() : BigDecimal.ZERO)
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

    public Map<String, Object> getAvailableResources(String finishedPartNumber) {
        ProductProcess proc = processRepo.findByFinishedPartNumber(finishedPartNumber).orElse(null);
        Double caliber = null;
        if (proc != null) {
            caliber = capacityMatcher.extractCaliber(proc.getFinishedModelSpec());
        }
        List<WeavingMachineStatus> allMachines = weavingStatusRepo.findAll();
        List<CoexLineStatus> allLines = coexStatusRepo.findAll();
        return capacityMatcher.getIdleCompatibleResources(caliber, allMachines, allLines);
    }
}
