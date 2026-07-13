package com.company.scheduling.controller;

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

    // 🌟 将查询改为 POST，以接收各车间负责人的人工微调参数
    @PostMapping("/advanced-schedule")
    @PreAuthorize("hasAuthority('ROLE_PLANNER') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<?> getAdvancedSchedule(@RequestBody ScheduleAdjustmentRequest request, Principal principal) {
        Map<String, Object> result = estimationService.calculateAdvancedSchedule(request, principal.getName());
        return ResponseEntity.ok(result);
    }
}