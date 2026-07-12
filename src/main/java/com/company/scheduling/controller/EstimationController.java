package com.company.scheduling.controller;

import com.company.scheduling.service.EstimationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.security.Principal;

@RestController
@RequestMapping("/api/v1/workshops/estimation")
public class EstimationController {

    private final EstimationService estimationService;

    public EstimationController(EstimationService estimationService) {
        this.estimationService = estimationService;
    }

    @GetMapping("/dynamic-completion-time")
    @PreAuthorize("hasAuthority('ROLE_PLANNER') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<String> getDynamicCompletionTime(
            @RequestParam String machineId,
            @RequestParam BigDecimal targetQty,
            @RequestParam(required = false) BigDecimal customCapacity,
            Principal principal) { // 注入操作人，用于排产计划留痕

        String result = estimationService.calculateDynamicCompletionTime(machineId, targetQty, customCapacity, principal.getName());
        return ResponseEntity.ok(result);
    }
}