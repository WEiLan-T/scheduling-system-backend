package com.company.scheduling.controller;

import com.company.scheduling.dto.MultiOrderScheduleRequest;
import com.company.scheduling.dto.ScheduleAdjustmentRequest;
import com.company.scheduling.service.EstimationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/workshops/estimation")
public class EstimationController {

    private final EstimationService estimationService;

    public EstimationController(EstimationService estimationService) {
        this.estimationService = estimationService;
    }

    // 🌟 第一阶段：纯草稿推演
    @PostMapping("/preview")
    @PreAuthorize("hasAuthority('ROLE_PLANNER') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<?> preview(@RequestBody ScheduleAdjustmentRequest request, Principal principal) {
        return ResponseEntity.ok(estimationService.previewSchedule(request, principal.getName()));
    }

    // 🌟 第二阶段：接收人类修改后的确认版并落库
    @PostMapping("/commit")
    @PreAuthorize("hasAuthority('ROLE_PLANNER') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<String> commit(@RequestBody Map<String, Object> finalPayload, Principal principal) {
        return ResponseEntity.ok(estimationService.commitFinalSchedule(finalPayload, principal.getName()));
    }

    // 🌟 多订单并发排产预览
    @PostMapping("/preview-multi")
    @PreAuthorize("hasAuthority('ROLE_PLANNER') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<?> previewMulti(@RequestBody MultiOrderScheduleRequest request, Principal principal) {
        return ResponseEntity.ok(estimationService.previewMultiOrderSchedule(request, principal.getName()));
    }

    // 🌟 排产汇总查询
    @GetMapping("/schedule-summary")
    @PreAuthorize("hasAuthority('ROLE_PLANNER') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<?> scheduleSummary() {
        return ResponseEntity.ok(estimationService.getScheduleSummary());
    }

    // 🌟 排产执行状态对比
    @GetMapping("/execution-status/{orderId}")
    @PreAuthorize("hasAuthority('ROLE_PLANNER') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<?> executionStatus(@PathVariable String orderId) {
        return ResponseEntity.ok(estimationService.getScheduleExecutionStatus(orderId));
    }
}