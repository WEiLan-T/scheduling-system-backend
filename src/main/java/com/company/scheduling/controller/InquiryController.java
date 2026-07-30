package com.company.scheduling.controller;

import com.company.scheduling.dto.InquiryRequest;
import com.company.scheduling.service.EstimationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/api/v1/workshops/estimation")
public class InquiryController {

    private final EstimationService estimationService;

    public InquiryController(EstimationService estimationService) {
        this.estimationService = estimationService;
    }

    @PostMapping("/inquiry")
    @PreAuthorize("hasAuthority('ROLE_PLANNER') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<?> inquiry(@RequestBody InquiryRequest request, Principal principal) {
        return ResponseEntity.ok(estimationService.calculateInquiry(request, principal.getName()));
    }
}
