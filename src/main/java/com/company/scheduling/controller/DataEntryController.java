package com.company.scheduling.controller;

import com.company.scheduling.dto.CoexEntryRequest;
import com.company.scheduling.dto.InventoryAdjustRequest;
import com.company.scheduling.dto.WeavingEntryRequest;
import com.company.scheduling.service.DataEntryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/api/v1/workshops/integration")
public class DataEntryController {

    private final DataEntryService dataEntryService;

    public DataEntryController(DataEntryService dataEntryService) {
        this.dataEntryService = dataEntryService;
    }

    @PostMapping("/weaving/logs")
    @PreAuthorize("hasAuthority('ROLE_WEAVING_CLERK') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<String> submitWeavingLog(@RequestBody WeavingEntryRequest request, Principal principal) {
        String result = dataEntryService.recordWeavingData(request, principal.getName());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/coextrusion/logs")
    @PreAuthorize("hasAuthority('ROLE_COEX_CLERK') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<String> submitCoexLog(@RequestBody CoexEntryRequest request, Principal principal) {
        String result = dataEntryService.recordCoexData(request, principal.getName());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/inventory/adjust")
    @PreAuthorize("hasAuthority('ROLE_PLANNER') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<String> adjustInventory(@RequestBody InventoryAdjustRequest request, Principal principal) {
        String result = dataEntryService.manualAdjustInventory(request, principal.getName());
        return ResponseEntity.ok(result);
    }
}