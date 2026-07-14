package com.company.scheduling.controller;

import com.company.scheduling.domain.*;
import com.company.scheduling.dto.*;
import com.company.scheduling.service.DataEntryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/v1/workshops/integration")
public class DataEntryController {

    private final DataEntryService dataEntryService;

    public DataEntryController(DataEntryService dataEntryService) {
        this.dataEntryService = dataEntryService;
    }

    // ================== 🧶 织造执行层 ==================
    @GetMapping("/weaving/logs/list")
    @PreAuthorize("hasAuthority('ROLE_WEAVING_CLERK') or hasAuthority('ROLE_ADMIN') or hasAuthority('ROLE_PLANNER')")
    public ResponseEntity<List<WeavingDailyLog>> getWeavingLogs() {
        return ResponseEntity.ok(dataEntryService.getWeavingLogs());
    }

    @PostMapping("/weaving/logs")
    @PreAuthorize("hasAuthority('ROLE_WEAVING_CLERK') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<String> submitWeavingLog(@RequestBody WeavingEntryRequest request, Principal principal) {
        return ResponseEntity.ok(dataEntryService.recordWeavingData(request, principal.getName()));
    }

    @DeleteMapping("/weaving/logs/{id}")
    @PreAuthorize("hasAuthority('ROLE_WEAVING_CLERK') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<String> deleteWeavingLog(@PathVariable Integer id, Principal principal) {
        return ResponseEntity.ok(dataEntryService.deleteWeavingLog(id, principal.getName()));
    }

    // ================== 🗜️ 共挤执行层 ==================
    @GetMapping("/coextrusion/logs/list")
    @PreAuthorize("hasAuthority('ROLE_COEX_CLERK') or hasAuthority('ROLE_ADMIN') or hasAuthority('ROLE_PLANNER')")
    public ResponseEntity<List<CoexDailyLog>> getCoexLogs() {
        return ResponseEntity.ok(dataEntryService.getCoexLogs());
    }

    @PostMapping("/coextrusion/logs")
    @PreAuthorize("hasAuthority('ROLE_COEX_CLERK') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<String> submitCoexLog(@RequestBody CoexEntryRequest request, Principal principal) {
        return ResponseEntity.ok(dataEntryService.recordCoexData(request, principal.getName()));
    }

    @DeleteMapping("/coextrusion/logs/{id}")
    @PreAuthorize("hasAuthority('ROLE_COEX_CLERK') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<String> deleteCoexLog(@PathVariable Integer id, Principal principal) {
        return ResponseEntity.ok(dataEntryService.deleteCoexLog(id, principal.getName()));
    }

    // ================== 📦 库存与调账 ==================
    @PostMapping("/inventory/adjust")
    @PreAuthorize("hasAuthority('ROLE_PLANNER') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<String> adjustInventory(@RequestBody InventoryAdjustRequest request, Principal principal) {
        return ResponseEntity.ok(dataEntryService.manualAdjustInventory(request, principal.getName()));
    }

    @GetMapping("/inventory/list")
    @PreAuthorize("hasAuthority('ROLE_PLANNER') or hasAuthority('ROLE_ADMIN') or hasAuthority('ROLE_WEAVING_CLERK') or hasAuthority('ROLE_COEX_CLERK')")
    public ResponseEntity<List<VirtualWarehouse>> getInventoryList(@RequestParam(required = false) String keyword) {
        return ResponseEntity.ok(dataEntryService.searchInventory(keyword));
    }

    @PostMapping("/inventory/save")
    @PreAuthorize("hasAuthority('ROLE_PLANNER') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<String> saveInventory(@RequestBody VirtualWarehouse inventory, Principal principal) {
        return ResponseEntity.ok(dataEntryService.saveOrUpdateInventory(inventory, principal.getName()));
    }

    @DeleteMapping("/inventory/{id}")
    @PreAuthorize("hasAuthority('ROLE_PLANNER') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<String> deleteInventory(@PathVariable Integer id) {
        return ResponseEntity.ok(dataEntryService.deleteInventory(id));
    }
    @GetMapping("/weaving/machines")
    @PreAuthorize("hasAnyAuthority('ROLE_WEAVING_CLERK', 'ROLE_ADMIN', 'ROLE_PLANNER')")
    public ResponseEntity<List<WeavingMachineStatus>> getMachines() {
        return ResponseEntity.ok(dataEntryService.getAllWeavingMachines());
    }

    @GetMapping("/coextrusion/lines")
    @PreAuthorize("hasAnyAuthority('ROLE_COEX_CLERK', 'ROLE_ADMIN', 'ROLE_PLANNER')")
    public ResponseEntity<List<CoexLineStatus>> getLines() {
        return ResponseEntity.ok(dataEntryService.getAllCoexLines());
    }
}