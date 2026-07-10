package com.company.scheduling.controller;

import com.company.scheduling.service.EstimationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/workshops/estimation")
public class EstimationController {

    private final EstimationService estimationService;

    public EstimationController(EstimationService estimationService) {
        this.estimationService = estimationService;
    }

    // 接口路径更新为 /dynamic-completion-time
    @GetMapping("/dynamic-completion-time")
    @PreAuthorize("hasAuthority('ROLE_PLANNER')") // 🌟 仅限计划员访问
    public ResponseEntity<String> getDynamicCompletionTime(
            @RequestParam String machineId,
            @RequestParam BigDecimal targetQty,
            @RequestParam(required = false) BigDecimal customCapacity) { // required = false 表示这个参数可以不传

        // 调用 Service 层最新的联动计算引擎
        String result = estimationService.calculateDynamicCompletionTime(machineId, targetQty, customCapacity);

        return ResponseEntity.ok(result);
    }
}