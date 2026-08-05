package com.company.scheduling.service;

import com.company.scheduling.domain.*;
import com.company.scheduling.dto.*;
import com.company.scheduling.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.multipart.MultipartFile;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;

@Service
public class DataEntryService {

    private final WeavingDailyLogRepo weavingLogRepo;
    private final WeavingMachineStatusRepo weavingStatusRepo;
    private final CoexDailyLogRepo coexLogRepo;
    private final CoexLineStatusRepo coexStatusRepo;
    private final VirtualWarehouseRepo warehouseRepo;

    public DataEntryService(WeavingDailyLogRepo weavingLogRepo, WeavingMachineStatusRepo weavingStatusRepo,
                            CoexDailyLogRepo coexLogRepo, CoexLineStatusRepo coexStatusRepo,
                            VirtualWarehouseRepo warehouseRepo) {
        this.weavingLogRepo = weavingLogRepo; this.weavingStatusRepo = weavingStatusRepo;
        this.coexLogRepo = coexLogRepo; this.coexStatusRepo = coexStatusRepo; this.warehouseRepo = warehouseRepo;
    }

    // 🌟 导入/导出能力已拆分至独立Service，旧入口通过委托保持兼容
    @Autowired private WeavingImportService weavingImportService;
    @Autowired private CoexImportService coexImportService;
    @Autowired private DataExportService dataExportService;

    public List<WeavingMachineStatus> getAllWeavingMachines() { return weavingStatusRepo.findAll(); }
    public List<CoexLineStatus> getAllCoexLines() { return coexStatusRepo.findAll(); }
    public List<WeavingDailyLog> getWeavingLogs() { return weavingLogRepo.findAll(); }
    public List<CoexDailyLog> getCoexLogs() { return coexLogRepo.findAll(); }

    @Transactional
    public String recordWeavingData(WeavingEntryRequest req, String currentUser) {
        WeavingMachineStatus status = weavingStatusRepo.findById(req.getMachineId()).orElse(new WeavingMachineStatus());
        status.setMachineId(req.getMachineId());
        status.setWorkshopId(req.getWorkshopId());
        status.setWarpSpec(req.getWarpSpec());
        status.setWeftSpec(req.getWeftSpec());
        status.setBobbinCount(req.getBobbinCount());
        status.setMachineStatus(req.getMachineStatus());
        status.setCaliberLimit(req.getCaliberLimit());
        status.setAdjacentMachine(req.getAdjacentMachine());
        status.setOperatorName(req.getOperatorName());
        status.setEnteredBy(currentUser);
        weavingStatusRepo.save(status);

        LocalDate entryDate = req.getEntryDate() != null ? req.getEntryDate() : LocalDate.now();
        String tapeCode = (req.getTapeNumber() == null || req.getTapeNumber().trim().isEmpty()) ? "DEFAULT" : req.getTapeNumber().trim();
        String modelSpec = req.getModelSpec() == null ? "" : req.getModelSpec();
        String shiftType = (req.getShift() == null || req.getShift().trim().isEmpty()) ? "白" : req.getShift().trim();

        WeavingDailyLog log;
        if (req.getId() != null) {
            log = weavingLogRepo.findById(req.getId().longValue()).orElseThrow(() -> new RuntimeException("找不到对应要修改的织造记录！"));
            String oldTapePn = log.getPartNumber() == null ? "" : log.getPartNumber();
            String oldTapeCode = log.getTapeCode() == null ? "DEFAULT" : log.getTapeCode();
            String newTapePn = req.getTapePartNumber() == null ? "" : req.getTapePartNumber();

            BigDecimal oldCap = log.getShiftOutput() != null ? log.getShiftOutput() : BigDecimal.ZERO;
            BigDecimal newCap = req.getCapacityPerDay() != null ? req.getCapacityPerDay() : BigDecimal.ZERO;

            if (!newTapePn.equals(oldTapePn) || !tapeCode.equals(oldTapeCode)) {
                updateVirtualWarehouse(oldTapePn, oldTapeCode, oldCap.negate(), log.getEntryDate() != null ? log.getEntryDate() : entryDate, currentUser);
                updateVirtualWarehouse(newTapePn, tapeCode, newCap, entryDate, currentUser);
            } else {
                updateVirtualWarehouse(newTapePn, tapeCode, newCap.subtract(oldCap), entryDate, currentUser);
            }
        } else {
            log = new WeavingDailyLog();
            updateVirtualWarehouse(req.getTapePartNumber(), tapeCode, req.getCapacityPerDay(), entryDate, currentUser);
        }

        log.setPartNumber(req.getTapePartNumber());
        log.setEntryDate(entryDate); log.setEntryYear(entryDate.getYear());
        log.setEntryMonth(entryDate.getMonthValue()); log.setEntryDay(entryDate.getDayOfMonth());
        log.setMachineNo(parseMachineNo(req.getMachineId()));
        log.setTapeCode(tapeCode);
        log.setModelSpec(modelSpec);
        log.setWarpThread(req.getWarpSpec()); log.setWeftThread(req.getWeftSpec());
        log.setShiftType(shiftType); log.setWorkerName(req.getOperatorName());
        log.setShiftOutput(req.getCapacityPerDay());
        log.setStandardCapacity(req.getStandardCapacity());
        log.setStandardHours(req.getStandardHours());
        log.setStandardHourCapacity(req.getStandardHourlyCapacity());
        log.setPerformanceHours(req.getPerformanceHours());
        log.setRemark(req.getRemarks());
        log.setDataQualityFlag(Boolean.FALSE.equals(req.getIsDataNormal()) ? "B" : "A");
        log.setDataSource("MANUAL");

        weavingLogRepo.save(log);
        return req.getId() != null ? "✅ 织造历史修改成功，库存已同步！" : "✅ 今日织造归档成功！";
    }

    /** 机台号容错解析（如"18#"取数字部分，无法解析时默认1） */
    private Integer parseMachineNo(String machineId) {
        if (machineId == null) return 1;
        StringBuilder digits = new StringBuilder();
        for (char c : machineId.toCharArray()) {
            if (Character.isDigit(c)) digits.append(c);
        }
        return digits.length() > 0 ? Integer.parseInt(digits.toString()) : 1;
    }

    @Transactional
    public String deleteWeavingLog(Long id, String currentUser) {
        WeavingDailyLog log = weavingLogRepo.findById(id).orElseThrow(() -> new RuntimeException("台账不存在"));
        if (log.getShiftOutput() != null && log.getShiftOutput().compareTo(BigDecimal.ZERO) > 0) {
            updateVirtualWarehouse(log.getPartNumber(), log.getTapeCode(), log.getShiftOutput().negate(), LocalDate.now(), currentUser);
        }
        weavingLogRepo.deleteById(id);
        return "🗑️ 织造台账已被物理废弃，带坯库存已扣减还原！";
    }

    @Transactional
    public String recordCoexData(CoexEntryRequest req, String currentUser) {
        if (req.getTapePartNumber() == null || req.getTapePartNumber().trim().isEmpty()) {
            if (req.getTapeNumber() == null || req.getTapeNumber().trim().isEmpty()) throw new RuntimeException("操作失败：必须提供带坯物理编号！");
            VirtualWarehouse vw = warehouseRepo.findLatestSnapshot().stream()
                    .filter(v -> req.getTapeNumber().trim().equals(v.getTapeCode()))
                    .findFirst().orElseThrow(() -> new RuntimeException("找不到带坯卷号！"));
            req.setTapePartNumber(vw.getPartNumber());
        }
        CoexLineStatus status = coexStatusRepo.findById(req.getLineId()).orElse(new CoexLineStatus());
        status.setLineId(req.getLineId()); status.setWorkshopId(req.getWorkshopId());
        status.setCaliberLimit(req.getCaliberLimit()); status.setLineStatus(req.getLineStatus()); status.setEnteredBy(currentUser);
        coexStatusRepo.save(status);

        LocalDate logDate = req.getEntryDate() != null ? req.getEntryDate() : LocalDate.now();

        CoexDailyLog log;
        if (req.getId() != null) {
            log = coexLogRepo.findById(req.getId().longValue()).orElseThrow(() -> new RuntimeException("台账不存在！"));
            BigDecimal oldDemand = log.getCapacityMeters() != null ? log.getCapacityMeters() : BigDecimal.ZERO;
            BigDecimal newDemand = req.getTapeDemandQty() != null ? req.getTapeDemandQty() : BigDecimal.ZERO;
            String tapeCode = req.getTapeNumber() != null ? req.getTapeNumber() : "";
            // 修改时按差额调整库存（旧台账已占用需求量）
            updateVirtualWarehouse(req.getTapePartNumber(), tapeCode, oldDemand.subtract(newDemand), logDate, currentUser);
        } else {
            log = new CoexDailyLog();
            updateVirtualWarehouse(req.getTapePartNumber(), req.getTapeNumber(), req.getTapeDemandQty().negate(), logDate, currentUser);
        }

        log.setLogDate(logDate);
        log.setMachineNo(req.getLineId() != null ? req.getLineId() : "");
        log.setProductModel(req.getFinishedModelSpec() != null ? req.getFinishedModelSpec() : "");
        log.setColor("");
        log.setCapacityMeters(req.getTapeDemandQty());
        log.setDataQualityFlag(Boolean.FALSE.equals(req.getIsDataNormal()) ? "B" : "A");
        log.setDataSource("MANUAL");
        coexLogRepo.save(log);

        return req.getId() != null ? "✅ 共挤台账修改成功！" : "✅ 共挤台账归档成功，库存已扣除。";
    }

    @Transactional
    public String deleteCoexLog(Long id, String currentUser) {
        CoexDailyLog log = coexLogRepo.findById(id).orElseThrow(() -> new RuntimeException("台账不存在"));
        if (log.getCapacityMeters() != null && log.getCapacityMeters().compareTo(BigDecimal.ZERO) > 0) {
            // 手工录入的共挤台账machineNo存的是产线号，此处无法反查带坯零件号，仅提示人工核对
            updateVirtualWarehouse(null, log.getMachineNo(), log.getCapacityMeters(), LocalDate.now(), currentUser);
        }
        coexLogRepo.deleteById(id);
        return "🗑️ 共挤台账已废弃，请人工核对库存退还！";
    }

    // =========================================================================
    // 🌟 动态表头及安全转换核心组件
    // =========================================================================
    private String getCellValueAsString(Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING: return cell.getStringCellValue().trim();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) return cell.getDateCellValue().toString();
                double val = cell.getNumericCellValue();
                if (val == (long) val) return String.valueOf((long) val);
                return String.valueOf(val);
            case BOOLEAN: return String.valueOf(cell.getBooleanCellValue());
            case FORMULA: try { return cell.getStringCellValue().trim(); } catch (Exception e) { return String.valueOf(cell.getNumericCellValue()); }
            default: return "";
        }
    }

    // =========================================================================
    // 🧶 织造车间 Excel 导入/导出（已迁移至 WeavingImportService / DataExportService）
    // =========================================================================

    /**
     * @deprecated 已迁移至 {@link WeavingImportService#importWeavingExcel(MultipartFile)}，
     * 此方法仅为兼容旧Controller保留的委托包装
     */
    @Deprecated
    public String importWeavingExcel(MultipartFile file, String currentUser) throws Exception {
        return weavingImportService.importWeavingExcel(file).getMessage();
    }

    /**
     * @deprecated 已迁移至 {@link DataExportService#exportWeavingToExcel()}，
     * 此方法仅为兼容旧Controller保留的委托包装
     */
    @Deprecated
    public byte[] exportWeavingToExcel() throws Exception {
        return dataExportService.exportWeavingToExcel();
    }

    // =========================================================================
    // 🗜️ 共挤车间 Excel 导入/导出（已迁移至 CoexImportService / DataExportService）
    // =========================================================================

    /**
     * @deprecated 已迁移至 {@link CoexImportService#importCoexExcel(MultipartFile)}，
     * 此方法仅为兼容旧Controller保留的委托包装
     */
    @Deprecated
    public String importCoexExcel(MultipartFile file, String currentUser) throws Exception {
        return coexImportService.importCoexExcel(file).getMessage();
    }

    /**
     * @deprecated 已迁移至 {@link DataExportService#exportCoexToExcel()}，
     * 此方法仅为兼容旧Controller保留的委托包装
     */
    @Deprecated
    public byte[] exportCoexToExcel() throws Exception {
        return dataExportService.exportCoexToExcel();
    }

    // =========================================================================
    // ⚙️ 工艺路线 Excel 导入
    // =========================================================================
    @Transactional
    public String importProcessExcel(MultipartFile file, String currentUser) throws Exception {
        if (file == null || file.isEmpty()) throw new RuntimeException("文件为空！");
        int successCount = 0; int skipCount = 0;

        try (InputStream is = file.getInputStream(); Workbook workbook = WorkbookFactory.create(is)) {
            Sheet sheet = workbook.getSheetAt(0);

            Row headerRow = sheet.getRow(0);
            int colFinPn=-1, colFinModel=-1, colTapePn=-1, colTapeModel=-1, colWarp=-1, colWeft=-1;

            if (headerRow != null) {
                for (int j = 0; j < headerRow.getLastCellNum(); j++) {
                    String h = getCellValueAsString(headerRow.getCell(j)).trim().replaceAll("\\s+", "");
                    if(h.contains("成品") && (h.contains("零件") || h.contains("号"))) colFinPn = j;
                    else if(h.contains("成品") && (h.contains("规格") || h.contains("型号"))) colFinModel = j;
                    else if(h.contains("带坯") && (h.contains("零件") || h.contains("号"))) colTapePn = j;
                    else if(h.contains("带坯") && (h.contains("规格") || h.contains("型号"))) colTapeModel = j;
                    else if(h.contains("经线")) colWarp = j;
                    else if(h.contains("纬线")) colWeft = j;
                }
            }

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i); if (row == null) continue;

                String finishedPartNumber = colFinPn >= 0 ? getCellValueAsString(row.getCell(colFinPn)) : "";
                if (finishedPartNumber.isEmpty()) { skipCount++; continue; }

                // 天然自带去重覆盖逻辑 (UPSERT)
                ProductProcess proc = processRepo.findByFinishedPartNumber(finishedPartNumber).orElse(new ProductProcess());
                proc.setFinishedPartNumber(finishedPartNumber);
                if(colFinModel >= 0) proc.setFinishedModelSpec(getCellValueAsString(row.getCell(colFinModel)));

                String tapePn = colTapePn >= 0 ? getCellValueAsString(row.getCell(colTapePn)) : "DEFAULT";
                proc.setTapePartNumber(tapePn.isEmpty() ? "DEFAULT" : tapePn);

                if(colTapeModel >= 0) proc.setTapeModelSpec(getCellValueAsString(row.getCell(colTapeModel)));
                if(colWarp >= 0) proc.setWarpSpec(getCellValueAsString(row.getCell(colWarp)));
                if(colWeft >= 0) proc.setWeftSpec(getCellValueAsString(row.getCell(colWeft)));

                proc.setEnteredBy(currentUser);
                processRepo.save(proc);
                successCount++;
            }
        }
        return "📊 工艺 Excel 解析完毕！导入/更新 " + successCount + " 条，跳过空行 " + skipCount + " 条。";
    }

    public byte[] exportProcessToExcel() throws Exception {
        List<ProductProcess> processes = processRepo.findAll();
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("全流水线工艺BOM");
            Row headerRow = sheet.createRow(0);
            String[] headers = {"成品零件号", "成品规格型号", "带坯零件号", "带坯规格型号", "经线型号", "纬线型号"};

            CellStyle headerStyle = workbook.createCellStyle(); Font font = workbook.createFont(); font.setBold(true); headerStyle.setFont(font);
            for (int i = 0; i < headers.length; i++) { Cell cell = headerRow.createCell(i); cell.setCellValue(headers[i]); cell.setCellStyle(headerStyle); }

            int rowIdx = 1;
            for (ProductProcess proc : processes) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(proc.getFinishedPartNumber() != null ? proc.getFinishedPartNumber() : "");
                row.createCell(1).setCellValue(proc.getFinishedModelSpec() != null ? proc.getFinishedModelSpec() : "");
                row.createCell(2).setCellValue(proc.getTapePartNumber() != null ? proc.getTapePartNumber() : "");
                row.createCell(3).setCellValue(proc.getTapeModelSpec() != null ? proc.getTapeModelSpec() : "");
                row.createCell(4).setCellValue(proc.getWarpSpec() != null ? proc.getWarpSpec() : "");
                row.createCell(5).setCellValue(proc.getWeftSpec() != null ? proc.getWeftSpec() : "");
            }
            for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);
            workbook.write(bos); return bos.toByteArray();
        }
    }

    // ==========================================
    // 📦 智能库存中枢
    // ==========================================
    @Transactional
    public String manualAdjustInventory(InventoryAdjustRequest req, String currentUser) {
        if (req.getTapePartNumber() == null || req.getTapePartNumber().trim().isEmpty()) throw new RuntimeException("调账失败：带坯零件号不能为空！");
        if (req.getAdjustMeters() == null) throw new RuntimeException("调账失败：调账米数不能为空！");
        updateVirtualWarehouse(req.getTapePartNumber().trim(), "DEFAULT", req.getAdjustMeters(), req.getEntryDate() != null ? req.getEntryDate() : LocalDate.now(), currentUser);
        String action = req.getAdjustMeters().compareTo(BigDecimal.ZERO) >= 0 ? "增加" : "扣减";
        return "📦 手动调账成功！已为带坯 [" + req.getTapePartNumber() + "] (批次: DEFAULT) " + action + " " + req.getAdjustMeters().abs() + " 米。";
    }

    private void updateVirtualWarehouse(String partNumber, String tapeCode, BigDecimal changeMeters, LocalDate entryDate, String currentUser) {
        if (partNumber == null || partNumber.isEmpty() || changeMeters == null) return;
        String finalTapeCode = (tapeCode == null || tapeCode.trim().isEmpty()) ? "DEFAULT" : tapeCode.trim();
        LocalDate snapshotDate = entryDate != null ? entryDate : LocalDate.now();

        // 手工增减优先计入最新一期快照；若快照中无该条目，则写入指定日期的快照记录
        VirtualWarehouse warehouse = warehouseRepo.findLatestSnapshot().stream()
                .filter(v -> partNumber.equals(v.getPartNumber()) && finalTapeCode.equals(v.getTapeCode()))
                .findFirst().orElse(null);
        if (warehouse == null) {
            List<VirtualWarehouse> byDate = warehouseRepo.findByPartNumberAndTapeCodeAndSnapshotDate(partNumber, finalTapeCode, snapshotDate);
            warehouse = byDate.isEmpty() ? new VirtualWarehouse() : byDate.get(0);
        }
        warehouse.setPartNumber(partNumber);
        warehouse.setTapeCode(finalTapeCode);
        if (warehouse.getSnapshotDate() == null) warehouse.setSnapshotDate(snapshotDate);
        BigDecimal current = warehouse.getStockMeters() != null ? warehouse.getStockMeters() : BigDecimal.ZERO;
        warehouse.setStockMeters(current.add(changeMeters));
        warehouseRepo.save(warehouse);
    }

    public List<VirtualWarehouse> searchInventory(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) return warehouseRepo.findLatestSnapshot();
        String kw = keyword.trim();
        return warehouseRepo.findLatestSnapshot().stream()
                .filter(v -> v.getPartNumber() != null && v.getPartNumber().toLowerCase().contains(kw.toLowerCase()))
                .collect(java.util.stream.Collectors.toList());
    }

    @Transactional
    public String saveOrUpdateInventory(VirtualWarehouse inv, String currentUser) {
        if (inv.getPartNumber() == null || inv.getPartNumber().trim().isEmpty()) throw new RuntimeException("保存失败：零件号不能为空！");
        String finalCode = (inv.getTapeCode() == null || inv.getTapeCode().trim().isEmpty()) ? "DEFAULT" : inv.getTapeCode().trim();
        LocalDate snapshotDate = inv.getSnapshotDate() != null ? inv.getSnapshotDate() : LocalDate.now();
        if (inv.getId() != null) {
            VirtualWarehouse existing = warehouseRepo.findById(inv.getId()).orElse(new VirtualWarehouse());
            existing.setPartNumber(inv.getPartNumber().trim()); existing.setTapeCode(finalCode);
            existing.setModelSpec(inv.getModelSpec()); existing.setWarpThread(inv.getWarpThread()); existing.setWeftThread(inv.getWeftThread());
            existing.setStockMeters(inv.getStockMeters()); existing.setStockType(inv.getStockType());
            existing.setRemark(inv.getRemark());
            if (existing.getSnapshotDate() == null) existing.setSnapshotDate(snapshotDate);
            warehouseRepo.save(existing);
            return "📦 库存档案信息修正成功！";
        } else {
            List<VirtualWarehouse> existingList = warehouseRepo.findByPartNumberAndTapeCodeAndSnapshotDate(inv.getPartNumber().trim(), finalCode, snapshotDate);
            if (!existingList.isEmpty()) {
                VirtualWarehouse target = existingList.get(0);
                BigDecimal add = inv.getStockMeters() != null ? inv.getStockMeters() : BigDecimal.ZERO;
                BigDecimal current = target.getStockMeters() != null ? target.getStockMeters() : BigDecimal.ZERO;
                target.setStockMeters(current.add(add));
                warehouseRepo.save(target);
                return "📦 发现相同(零件号+带坯编号)同快照条目，库存数量已自动合并追加！";
            } else {
                inv.setPartNumber(inv.getPartNumber().trim());
                inv.setTapeCode(finalCode);
                inv.setSnapshotDate(snapshotDate);
                warehouseRepo.save(inv);
                return "📦 成功建立全新(零件号+带坯编号)库存档案卡片！";
            }
        }
    }

    @Autowired private ProductProcessRepo processRepo;
    public List<ProductProcess> getAllProcesses() { return processRepo.findAll(); }

    @Transactional
    public String saveOrUpdateProcess(ProductProcess proc, String currentUser) {
        if (proc.getId() != null) {
            ProductProcess existing = processRepo.findById(proc.getId()).orElse(new ProductProcess());
            existing.setFinishedPartNumber(proc.getFinishedPartNumber().trim()); existing.setTapePartNumber(proc.getTapePartNumber().trim());
            existing.setFinishedModelSpec(proc.getFinishedModelSpec()); existing.setTapeModelSpec(proc.getTapeModelSpec());
            existing.setWarpSpec(proc.getWarpSpec()); existing.setWeftSpec(proc.getWeftSpec()); existing.setEnteredBy(currentUser);
            processRepo.save(existing); return "✅ 工艺路线修正成功！";
        } else {
            proc.setEnteredBy(currentUser); processRepo.save(proc); return "✅ 全新成品工艺BOM路线建立成功！";
        }
    }

    @Transactional public String deleteProcess(Integer id) { processRepo.deleteById(id); return "🗑️ 该工艺BOM链条已解除！"; }
    @Transactional public String deleteInventory(Long id) { warehouseRepo.deleteById(id); return "⚠️ 数据条目已从物理磁盘抹除！"; }
}
