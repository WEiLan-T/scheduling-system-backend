package com.company.scheduling.controller;

import com.company.scheduling.service.EstimationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workshops/estimation")
public class EstimationController {

    private final EstimationService estimationService;

    public EstimationController(EstimationService estimationService) {
        this.estimationService = estimationService;
    }

    // 使用 GET 请求，因为我们只是查询，不修改数据
    @GetMapping("/completion-time")
    public ResponseEntity<String> getCompletionTime(
            @RequestParam UUID tapePartNumber,
            @RequestParam BigDecimal targetQty) {

        String result = estimationService.calculateEstimatedCompletionTime(tapePartNumber, targetQty);
        return ResponseEntity.ok(result);
    }
}