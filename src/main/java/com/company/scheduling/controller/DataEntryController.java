package com.company.scheduling.controller;

import com.company.scheduling.domain.VirtualWarehouse;
import com.company.scheduling.dto.CoexEntryRequest;
import com.company.scheduling.dto.InventoryAdjustRequest;
import com.company.scheduling.dto.WeavingEntryRequest;
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

    // ================== 车间一线执行层接口 ==================

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

    // ================== 计划指挥层：库存大盘接口 ==================

    // 快捷调账接口 (保留兼容)
    @PostMapping("/inventory/adjust")
    @PreAuthorize("hasAuthority('ROLE_PLANNER') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<String> adjustInventory(@RequestBody InventoryAdjustRequest request, Principal principal) {
        String result = dataEntryService.manualAdjustInventory(request, principal.getName());
        return ResponseEntity.ok(result);
    }

    // 🌟 核心查询：获取库存列表 (所有人皆可查看)
    @GetMapping("/inventory/list")
    @PreAuthorize("hasAuthority('ROLE_PLANNER') or hasAuthority('ROLE_ADMIN') or hasAuthority('ROLE_WEAVING_CLERK') or hasAuthority('ROLE_COEX_CLERK')")
    public ResponseEntity<List<VirtualWarehouse>> getInventoryList(@RequestParam(required = false) String keyword) {
        return ResponseEntity.ok(dataEntryService.searchInventory(keyword));
    }

    // 🌟 核心建档：新增或修改库存记录 (限计划员和管理员)
    @PostMapping("/inventory/save")
    @PreAuthorize("hasAuthority('ROLE_PLANNER') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<String> saveInventory(@RequestBody VirtualWarehouse inventory, Principal principal) {
        return ResponseEntity.ok(dataEntryService.saveOrUpdateInventory(inventory, principal.getName()));
    }

    // 🌟 核心销毁：删除库存记录 (限计划员和管理员)
    @DeleteMapping("/inventory/{id}")
    @PreAuthorize("hasAuthority('ROLE_PLANNER') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<String> deleteInventory(@PathVariable Integer id) {
        return ResponseEntity.ok(dataEntryService.deleteInventory(id));
    }
}