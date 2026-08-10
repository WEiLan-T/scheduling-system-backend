package com.company.scheduling.controller;

import com.company.scheduling.domain.*;
import com.company.scheduling.dto.*;
import com.company.scheduling.service.CoexImportService;
import com.company.scheduling.service.DataEntryService;
import com.company.scheduling.service.DataExportService;
import com.company.scheduling.service.InventoryCalculationService;
import com.company.scheduling.service.InventoryImportService;
import com.company.scheduling.service.WeavingImportService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.security.Principal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/workshops/integration")
public class DataEntryController {

    private final DataEntryService dataEntryService;
    private final WeavingImportService weavingImportService;
    private final CoexImportService coexImportService;
    private final InventoryImportService inventoryImportService;
    private final DataExportService dataExportService;
    private final InventoryCalculationService inventoryCalculationService;

    public DataEntryController(DataEntryService dataEntryService,
                               WeavingImportService weavingImportService,
                               CoexImportService coexImportService,
                               InventoryImportService inventoryImportService,
                               DataExportService dataExportService,
                               InventoryCalculationService inventoryCalculationService) {
        this.dataEntryService = dataEntryService;
        this.weavingImportService = weavingImportService;
        this.coexImportService = coexImportService;
        this.inventoryImportService = inventoryImportService;
        this.dataExportService = dataExportService;
        this.inventoryCalculationService = inventoryCalculationService;
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
    public ResponseEntity<String> deleteWeavingLog(@PathVariable Long id, Principal principal) {
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
    public ResponseEntity<String> deleteCoexLog(@PathVariable Long id, Principal principal) {
        return ResponseEntity.ok(dataEntryService.deleteCoexLog(id, principal.getName()));
    }

    // ================== 🧶 织造车间 Excel 交互 ==================
    @PostMapping("/weaving/import")
    @PreAuthorize("hasAuthority('ROLE_WEAVING_CLERK') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ImportResult> importWeaving(@RequestParam("file") MultipartFile file) {
        ImportResult result = weavingImportService.importWeavingExcel(file);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/weaving/export")
    @PreAuthorize("hasAuthority('ROLE_WEAVING_CLERK') or hasAuthority('ROLE_ADMIN') or hasAuthority('ROLE_PLANNER')")
    public ResponseEntity<byte[]> exportWeaving() {
        byte[] data = dataExportService.exportWeavingToExcel();
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=weaving_export.xlsx")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(data);
    }

    // ================== 🗜️ 共挤车间 Excel 交互 ==================
    @PostMapping("/coextrusion/import")
    @PreAuthorize("hasAuthority('ROLE_COEX_CLERK') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ImportResult> importCoex(@RequestParam("file") MultipartFile file) {
        ImportResult result = coexImportService.importCoexExcel(file);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/coextrusion/export")
    @PreAuthorize("hasAuthority('ROLE_COEX_CLERK') or hasAuthority('ROLE_ADMIN') or hasAuthority('ROLE_PLANNER')")
    public ResponseEntity<byte[]> exportCoex() {
        byte[] data = dataExportService.exportCoexToExcel();
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=coex_export.xlsx")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(data);
    }

    // ================== 📦 库存 Excel 导入与核对 ==================
    @PostMapping("/inventory/import")
    @PreAuthorize("hasAuthority('ROLE_PLANNER') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<ImportResult> importInventory(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "snapshotDate", required = false) String snapshotDateStr) {
        // snapshotDate格式: yyyy-MM-dd，如"2026-07-31"；不传则默认当前日期
        LocalDate snapshotDate = (snapshotDateStr != null && !snapshotDateStr.isEmpty())
                ? LocalDate.parse(snapshotDateStr)
                : LocalDate.now();
        ImportResult result = inventoryImportService.importInventoryExcel(file, snapshotDate);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/inventory/export")
    @PreAuthorize("hasAuthority('ROLE_PLANNER') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<byte[]> exportInventory(
            @RequestParam(value = "snapshotDate", required = false) String snapshotDateStr) {
        // null表示导出最新快照
        LocalDate snapshotDate = (snapshotDateStr != null && !snapshotDateStr.isEmpty())
                ? LocalDate.parse(snapshotDateStr)
                : null;
        byte[] data = dataExportService.exportInventoryWithReconciliation(snapshotDate);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=inventory_reconciliation.xlsx")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(data);
    }

    @GetMapping("/inventory/reconciliation")
    @PreAuthorize("hasAuthority('ROLE_PLANNER') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<List<InventoryReconciliationDTO>> getReconciliationReport(
            @RequestParam(value = "snapshotDate", required = false) String snapshotDateStr) {
        LocalDate snapshotDate = (snapshotDateStr != null && !snapshotDateStr.isEmpty())
                ? LocalDate.parse(snapshotDateStr)
                : null;
        List<InventoryReconciliationDTO> report = inventoryImportService.getReconciliationReport(snapshotDate);
        return ResponseEntity.ok(report);
    }

    @PostMapping("/inventory/reconciliation/confirm/{id}")
    @PreAuthorize("hasAuthority('ROLE_PLANNER') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<Map<String, String>> confirmReconciliation(@PathVariable Long id) {
        inventoryImportService.confirmReconciliation(id);
        return ResponseEntity.ok(Map.of("message", "核对确认成功"));
    }

    /**
     * 推算日库存汇总（只读）：某日库存 = 最近一期月度权威快照 + Σ织造产量 − Σ共挤消耗
     * 参数：date 单日；或 startDate/endDate 区间；全部缺省时默认查询当天
     */
    @GetMapping("/inventory/daily-summary")
    @PreAuthorize("hasAuthority('ROLE_PLANNER') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<List<InventoryDailySummaryDTO>> getInventoryDailySummary(
            @RequestParam(value = "date", required = false) String dateStr,
            @RequestParam(value = "startDate", required = false) String startDateStr,
            @RequestParam(value = "endDate", required = false) String endDateStr) {
        LocalDate start;
        LocalDate end;
        if (dateStr != null && !dateStr.trim().isEmpty()) {
            start = parseBusinessDate(dateStr, "date");
            end = start;
        } else {
            // date 与 startDate/endDate 均缺省时默认查询当天
            end = (endDateStr != null && !endDateStr.trim().isEmpty())
                    ? parseBusinessDate(endDateStr, "endDate") : LocalDate.now();
            start = (startDateStr != null && !startDateStr.trim().isEmpty())
                    ? parseBusinessDate(startDateStr, "startDate") : end;
        }
        if (start.isAfter(end)) {
            throw new RuntimeException("日期参数非法：起始日期(" + start + ")不能晚于结束日期(" + end + ")！");
        }
        return ResponseEntity.ok(inventoryCalculationService.calculateDailySummary(start, end));
    }

    /**
     * 日期参数解析与合法性校验（yyyy-MM-dd）：
     * 非法格式或年份越界时抛出带清晰业务消息的异常（由全局异常处理器转为400），
     * 避免超范围日期穿透到 JDBC 层报"时间戳超出范围"
     */
    private LocalDate parseBusinessDate(String value, String paramName) {
        LocalDate date;
        try {
            date = LocalDate.parse(value.trim());
        } catch (Exception e) {
            throw new RuntimeException("日期参数[" + paramName + "]格式非法：\"" + value + "\"，要求 yyyy-MM-dd（如 2026-08-10）");
        }
        // PostgreSQL date 安全范围校验，防止极值日期导致 JDBC 绑定溢出
        if (date.getYear() < 1900 || date.getYear() > 9999) {
            throw new RuntimeException("日期参数[" + paramName + "]超出允许范围(1900-01-01 至 9999-12-31)：" + date);
        }
        return date;
    }

    // ================== 🔍 数据质量重检（B级数据） ==================
    @PostMapping("/data-quality/recheck")
    @PreAuthorize("hasAuthority('ROLE_ADMIN') or hasAuthority('ROLE_PLANNER')")
    public ResponseEntity<Map<String, Object>> recheckGradeB() {
        // 触发织造B级数据重新检查
        Map<String, Object> result = new HashMap<>();
        result.put("weaving", weavingImportService.recheckGradeBRecords());
        return ResponseEntity.ok(result);
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
    public ResponseEntity<String> deleteInventory(@PathVariable Long id) {
        return ResponseEntity.ok(dataEntryService.deleteInventory(id));
    }

    // ================== ✂️ 带坯分切 ==================
    @PostMapping("/inventory/split")
    @PreAuthorize("hasAuthority('ROLE_PLANNER') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<String> splitInventory(@RequestBody TapeSplitRequest request) {
        return ResponseEntity.ok(dataEntryService.splitTape(request.getId(), request.getLengths()));
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

    @GetMapping("/process/list")
    @PreAuthorize("hasAuthority('ROLE_PLANNER') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<List<ProductProcess>> getProcessList() {
        return ResponseEntity.ok(dataEntryService.getAllProcesses());
    }

    @PostMapping("/process/save")
    @PreAuthorize("hasAuthority('ROLE_PLANNER') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<String> saveProcess(@RequestBody ProductProcess process, Principal principal) {
        return ResponseEntity.ok(dataEntryService.saveOrUpdateProcess(process, principal.getName()));
    }

    @DeleteMapping("/process/{id}")
    @PreAuthorize("hasAuthority('ROLE_PLANNER') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<String> deleteProcess(@PathVariable Integer id) {
        return ResponseEntity.ok(dataEntryService.deleteProcess(id));
    }

    @PostMapping("/process/import")
    @PreAuthorize("hasAuthority('ROLE_PLANNER') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<String> importProcesses(@RequestParam("file") MultipartFile file, Principal principal) {
        try {
            String result = dataEntryService.importProcessExcel(file, principal.getName());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Excel 导入失败，原因：" + e.getMessage());
        }
    }

    @GetMapping("/process/export")
    @PreAuthorize("hasAuthority('ROLE_PLANNER') or hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<byte[]> exportProcesses() {
        try {
            byte[] excelBytes = dataEntryService.exportProcessToExcel();
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
            headers.setContentDispositionFormData("attachment", "Product_Process_BOM.xlsx");
            headers.setCacheControl("must-revalidate, post-check=0, pre-check=0");
            return new ResponseEntity<>(excelBytes, headers, org.springframework.http.HttpStatus.OK);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(null);
        }
    }
}