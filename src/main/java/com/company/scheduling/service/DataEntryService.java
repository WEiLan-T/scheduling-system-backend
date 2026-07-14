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

    public List<WeavingMachineStatus> getAllWeavingMachines() { return weavingStatusRepo.findAll(); }
    public List<CoexLineStatus> getAllCoexLines() { return coexStatusRepo.findAll(); }
    public List<WeavingDailyLog> getWeavingLogs() { return weavingLogRepo.findAllByOrderByEntryDateDesc(); }
    public List<CoexDailyLog> getCoexLogs() { return coexLogRepo.findAllByOrderByEntryDateDesc(); }

    // ==========================================
    // 🧶 织造车间：增改逻辑与【型号+编号】双重库存平补
    // ==========================================
    @Transactional
    public String recordWeavingData(WeavingEntryRequest req, String currentUser) {
        WeavingMachineStatus status = weavingStatusRepo.findById(req.getMachineId()).orElse(new WeavingMachineStatus());
        status.setMachineId(req.getMachineId()); status.setWorkshopId(req.getWorkshopId()); status.setWarpSpec(req.getWarpSpec());
        status.setWeftSpec(req.getWeftSpec()); status.setBobbinCount(req.getBobbinCount()); status.setMachineStatus(req.getMachineStatus());
        status.setCaliberLimit(req.getCaliberLimit()); status.setAdjacentMachine(req.getAdjacentMachine());
        status.setOperatorName(req.getOperatorName()); status.setEnteredBy(currentUser);
        weavingStatusRepo.save(status);

        WeavingDailyLog log;
        if (req.getId() != null) {
            log = weavingLogRepo.findById(req.getId()).orElseThrow(() -> new RuntimeException("找不到对应要修改的织造记录！"));

            // 🌟 核心修复 1：防止旧数据为 null 导致的比对崩溃
            String oldTapePn = log.getTapePartNumber() == null ? "" : log.getTapePartNumber();
            String oldTapeNum = log.getTapeNumber() == null ? "DEFAULT" : log.getTapeNumber();
            String newTapePn = req.getTapePartNumber() == null ? "" : req.getTapePartNumber();
            String newTapeNum = req.getTapeNumber() == null || req.getTapeNumber().trim().isEmpty() ? "DEFAULT" : req.getTapeNumber().trim();

            BigDecimal oldCap = log.getCapacityPerDay() != null ? log.getCapacityPerDay() : BigDecimal.ZERO;
            BigDecimal newCap = req.getCapacityPerDay() != null ? req.getCapacityPerDay() : BigDecimal.ZERO;

            if (!newTapePn.equals(oldTapePn) || !newTapeNum.equals(oldTapeNum)) {
                updateVirtualWarehouse(oldTapePn, oldTapeNum, oldCap.negate(), req.getEntryDate(), currentUser);
                updateVirtualWarehouse(newTapePn, newTapeNum, newCap, req.getEntryDate(), currentUser);
            } else {
                updateVirtualWarehouse(newTapePn, newTapeNum, newCap.subtract(oldCap), req.getEntryDate(), currentUser);
            }
        } else {
            log = new WeavingDailyLog();
            updateVirtualWarehouse(req.getTapePartNumber(), req.getTapeNumber(), req.getCapacityPerDay(), req.getEntryDate(), currentUser);
        }

        log.setTapePartNumber(req.getTapePartNumber()); log.setTapeNumber(req.getTapeNumber());
        log.setMachineId(req.getMachineId()); log.setCapacityPerDay(req.getCapacityPerDay());
        log.setIsDataNormal(req.getIsDataNormal()); log.setRemarks(req.getRemarks());
        log.setEntryDate(req.getEntryDate()); log.setTotalDemand(req.getTotalDemand()); log.setEnteredBy(currentUser);
        weavingLogRepo.save(log);

        return req.getId() != null ? "✅ 织造历史修改成功，(型号+编号)对应的可用库存已智能重算！" : "✅ 今日织造合并归档落库成功！";
    }

    @Transactional
    public String deleteWeavingLog(Integer id, String currentUser) {
        WeavingDailyLog log = weavingLogRepo.findById(id).orElseThrow(() -> new RuntimeException("台账不存在"));
        if (log.getCapacityPerDay() != null && log.getCapacityPerDay().compareTo(BigDecimal.ZERO) > 0) {
            updateVirtualWarehouse(log.getTapePartNumber(), log.getTapeNumber(), log.getCapacityPerDay().negate(), LocalDate.now(), currentUser);
        }
        weavingLogRepo.deleteById(id);
        return "🗑️ 织造台账已被物理废弃，对应的带坯库存卡片已扣减还原！";
    }

    // ==========================================
    // 🗜️ 共挤车间：增改逻辑与【型号+编号】双重库存平补
    // ==========================================
    @Transactional
    public String recordCoexData(CoexEntryRequest req, String currentUser) {
        // 如果前端未传递带坯型号，则系统自动根据卷号去库存中寻找
        if (req.getTapePartNumber() == null || req.getTapePartNumber().trim().isEmpty()) {
            if (req.getTapeNumber() == null || req.getTapeNumber().trim().isEmpty()) {
                throw new RuntimeException("操作失败：必须提供消耗的带坯物理编号（卷号）！");
            }

            // 根据物理卷号去库存档案寻找
            VirtualWarehouse vw = warehouseRepo.findFirstByTapeNumber(req.getTapeNumber().trim())
                    .orElseThrow(() -> new RuntimeException("系统拦截：当前库存中找不到编号为 [" + req.getTapeNumber() + "] 的带坯卷，无法自动识别型号，请核对该卷号是否已入库！"));

            // 自动为请求对象补全带坯零件号，使接下来的库存平补逻辑正常运行
            req.setTapePartNumber(vw.getTapePartNumber());
        }
        CoexLineStatus status = coexStatusRepo.findById(req.getLineId()).orElse(new CoexLineStatus());
        status.setLineId(req.getLineId()); status.setWorkshopId(req.getWorkshopId());
        status.setCaliberLimit(req.getCaliberLimit()); status.setLineStatus(req.getLineStatus()); status.setEnteredBy(currentUser);
        coexStatusRepo.save(status);

        CoexDailyLog log;
        if (req.getId() != null) {
            log = coexLogRepo.findById(req.getId()).orElseThrow(() -> new RuntimeException("找不到对应要修改的共挤记录！"));

            // 🌟 核心修复 1：防空指针
            String oldTapePn = log.getTapePartNumber() == null ? "" : log.getTapePartNumber();
            String oldTapeNum = log.getTapeNumber() == null ? "DEFAULT" : log.getTapeNumber();
            String newTapePn = req.getTapePartNumber() == null ? "" : req.getTapePartNumber();
            String newTapeNum = req.getTapeNumber() == null || req.getTapeNumber().trim().isEmpty() ? "DEFAULT" : req.getTapeNumber().trim();

            BigDecimal oldDemand = log.getTapeDemandQty() != null ? log.getTapeDemandQty() : BigDecimal.ZERO;
            BigDecimal newDemand = req.getTapeDemandQty() != null ? req.getTapeDemandQty() : BigDecimal.ZERO;

            if (!newTapePn.equals(oldTapePn) || !newTapeNum.equals(oldTapeNum)) {
                updateVirtualWarehouse(oldTapePn, oldTapeNum, oldDemand, req.getEntryDate(), currentUser);
                updateVirtualWarehouse(newTapePn, newTapeNum, newDemand.negate(), req.getEntryDate(), currentUser);
            } else {
                updateVirtualWarehouse(newTapePn, newTapeNum, oldDemand.subtract(newDemand), req.getEntryDate(), currentUser);
            }
        } else {
            log = new CoexDailyLog();
            updateVirtualWarehouse(req.getTapePartNumber(), req.getTapeNumber(), req.getTapeDemandQty().negate(), req.getEntryDate(), currentUser);
        }

        log.setFinishedPartNumber(req.getFinishedPartNumber()); log.setLineId(req.getLineId());
        log.setCapacityPerDay(req.getCapacityPerDay()); log.setIsDataNormal(req.getIsDataNormal());
        log.setRemarks(req.getRemarks()); log.setTapeDemandQty(req.getTapeDemandQty());
        log.setTapePartNumber(req.getTapePartNumber()); log.setTapeNumber(req.getTapeNumber());
        log.setEntryDate(req.getEntryDate()); log.setEnteredBy(currentUser);
        coexLogRepo.save(log);

        return req.getId() != null ? "✅ 共挤台账修改成功，消耗库存已智能对账！" : "✅ 共挤台账归档成功，消耗带坯库存已自动扣除。";
    }

    @Transactional
    public String deleteCoexLog(Integer id, String currentUser) {
        CoexDailyLog log = coexLogRepo.findById(id).orElseThrow(() -> new RuntimeException("台账不存在"));
        if (log.getTapeDemandQty() != null && log.getTapeDemandQty().compareTo(BigDecimal.ZERO) > 0) {
            updateVirtualWarehouse(log.getTapePartNumber(), log.getTapeNumber(), log.getTapeDemandQty(), LocalDate.now(), currentUser);
        }
        coexLogRepo.deleteById(id);
        return "🗑️ 共挤台账已废弃，已退还扣减的指定编号带坯库存！";
    }

    // =========================================================================
// ⚙️ 核心增强：工艺路线 Excel 导入与导出引擎（支持空数据）
// =========================================================================

    /**
     * 从 Excel 导入工艺路线，采用 upsert 机制（存在则更新，不存在则插入）
     * 兼容空数据读取
     */
    @Transactional
    public String importProcessExcel(MultipartFile file, String currentUser) throws Exception {
        if (file == null || file.isEmpty()) {
            throw new RuntimeException("导入失败：上传的 Excel 文件为空！");
        }

        int successCount = 0;
        int skipCount = 0;

        try (InputStream is = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(is)) {

            Sheet sheet = workbook.getSheetAt(0);
            int rowCount = sheet.getPhysicalNumberOfRows();
            if (rowCount <= 1) {
                throw new RuntimeException("导入失败：Excel 档案内无有效数据（仅有表头或空白）！");
            }

            // 迭代每一行数据（跳过第0行表头）
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue; // 过滤空行
                }

                // 🌟 安全读取单元格方法：防止空指针与类型转换异常
                String finishedPartNumber = getCellValueAsString(row.getCell(0));
                String tapePartNumber = getCellValueAsString(row.getCell(1));
                String warpSpec = getCellValueAsString(row.getCell(2));
                String weftSpec = getCellValueAsString(row.getCell(3));

                // 防呆：如果最核心的“成品零件号”为空，该行视为无效行，选择跳过而非崩溃
                if (finishedPartNumber.isEmpty()) {
                    skipCount++;
                    continue;
                }

                // 查找并更新，或新建工艺
                ProductProcess proc = processRepo.findByFinishedPartNumber(finishedPartNumber)
                        .orElse(new ProductProcess());

                proc.setFinishedPartNumber(finishedPartNumber);
                proc.setTapePartNumber(tapePartNumber.isEmpty() ? "DEFAULT" : tapePartNumber);
                proc.setWarpSpec(warpSpec); // 支持空数据存入
                proc.setWeftSpec(weftSpec); // 支持空数据存入
                proc.setEnteredBy(currentUser);

                processRepo.save(proc);
                successCount++;
            }
        }

        return "📊 Excel 解析完毕！成功导入/更新工艺数据 " + successCount + " 条。"
                + (skipCount > 0 ? "（跳过成品号为空的无效数据 " + skipCount + " 条）" : "");
    }

    /**
     * 将当前的工艺路线数据库整体导出为标准的 Excel 文件，支持空单元格安全写出
     */
    public byte[] exportProcessToExcel() throws Exception {
        List<ProductProcess> processes = processRepo.findAll();

        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet("全流水线工艺BOM");

            // 1. 创建标题行及样式
            Row headerRow = sheet.createRow(0);
            String[] headers = {"成品零件号", "带坯零件号", "经线型号", "纬线型号"};

            CellStyle headerStyle = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(true);
            headerStyle.setFont(font);

            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // 2. 循环写入数据库实体
            int rowIdx = 1;
            for (ProductProcess proc : processes) {
                Row row = sheet.createRow(rowIdx++);

                // 🌟 采用安全赋值，若字段为空则写出空字符串单元格
                row.createCell(0).setCellValue(proc.getFinishedPartNumber() != null ? proc.getFinishedPartNumber() : "");
                row.createCell(1).setCellValue(proc.getTapePartNumber() != null ? proc.getTapePartNumber() : "");
                row.createCell(2).setCellValue(proc.getWarpSpec() != null ? proc.getWarpSpec() : "");
                row.createCell(3).setCellValue(proc.getWeftSpec() != null ? proc.getWeftSpec() : "");
            }

            // 3. 自动调整列宽
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(bos);
            return bos.toByteArray();
        }
    }

    /**
     * 辅助工具：提取各种类型单元格的值，并强转为 String，兼容空数据
     */
    private String getCellValueAsString(Cell cell) {
        if (cell == null) {
            return "";
        }
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue().trim();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                }
                // 防止类似零件号被转换成浮点数
                double val = cell.getNumericCellValue();
                if (val == (long) val) {
                    return String.valueOf((long) val);
                }
                return String.valueOf(val);
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return cell.getStringCellValue().trim();
                } catch (Exception e) {
                    return String.valueOf(cell.getNumericCellValue());
                }
            default:
                return "";
        }
    }

    // ==========================================
    // 📦 智能库存中枢：(型号, 编号) 唯一纽带合并与冲抵
    // ==========================================
    @Transactional
    public String manualAdjustInventory(InventoryAdjustRequest req, String currentUser) {
        if (req.getTapePartNumber() == null || req.getTapePartNumber().trim().isEmpty()) {
            throw new RuntimeException("调账失败：带坯零件号不能为空！");
        }
        if (req.getAdjustMeters() == null) {
            throw new RuntimeException("调账失败：调账米数不能为空！");
        }

        // 调用现有的库存更新逻辑，默认物理卷号为 "DEFAULT"
        updateVirtualWarehouse(
                req.getTapePartNumber().trim(),
                "DEFAULT",
                req.getAdjustMeters(),
                req.getEntryDate() != null ? req.getEntryDate() : LocalDate.now(),
                currentUser
        );

        String action = req.getAdjustMeters().compareTo(BigDecimal.ZERO) >= 0 ? "增加" : "扣减";
        return "📦 手动调账成功！已为带坯 [" + req.getTapePartNumber() + "] (批次: DEFAULT) "
                + action + " " + req.getAdjustMeters().abs() + " 米。";
    }
    private void updateVirtualWarehouse(String tapePartNumber, String tapeNumber, BigDecimal changeMeters, LocalDate entryDate, String currentUser) {
        if (tapePartNumber == null || tapePartNumber.isEmpty() || changeMeters == null) return;
        String finalTapeNum = (tapeNumber == null || tapeNumber.trim().isEmpty()) ? "DEFAULT" : tapeNumber.trim();

        // 🌟 核心：按照 型号 + 物理编号 双重条件进行合并与盘点
        VirtualWarehouse warehouse = warehouseRepo.findByTapePartNumberAndTapeNumber(tapePartNumber, finalTapeNum)
                .orElse(new VirtualWarehouse());

        warehouse.setTapePartNumber(tapePartNumber);
        warehouse.setTapeNumber(finalTapeNum);

        BigDecimal current = warehouse.getCurrentStockMeters() != null ? warehouse.getCurrentStockMeters() : BigDecimal.ZERO;
        warehouse.setCurrentStockMeters(current.add(changeMeters));
        warehouse.setEntryDate(entryDate);
        warehouse.setEnteredBy(currentUser);
        warehouseRepo.save(warehouse);
    }

    public List<VirtualWarehouse> searchInventory(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) return warehouseRepo.findAll();
        return warehouseRepo.findByTapePartNumberContainingIgnoreCase(keyword);
    }

    @Transactional
    public String saveOrUpdateInventory(VirtualWarehouse inv, String currentUser) {
        String finalNum = (inv.getTapeNumber() == null || inv.getTapeNumber().trim().isEmpty()) ? "DEFAULT" : inv.getTapeNumber().trim();
        if (inv.getId() != null) {
            VirtualWarehouse existing = warehouseRepo.findById(inv.getId()).orElse(new VirtualWarehouse());
            existing.setTapePartNumber(inv.getTapePartNumber()); existing.setTapeNumber(finalNum);
            existing.setFinishedPartNumber(inv.getFinishedPartNumber()); existing.setCurrentStockMeters(inv.getCurrentStockMeters());
            existing.setEntryDate(inv.getEntryDate() != null ? inv.getEntryDate() : LocalDate.now());
            existing.setEnteredBy(currentUser); warehouseRepo.save(existing);
            return "📦 库存档案信息修正成功！";
        } else {
            // 🌟 手工建档防重，自动合并
            Optional<VirtualWarehouse> existingOpt = warehouseRepo.findByTapePartNumberAndTapeNumber(inv.getTapePartNumber(), finalNum);
            if (existingOpt.isPresent()) {
                VirtualWarehouse target = existingOpt.get();
                target.setCurrentStockMeters(target.getCurrentStockMeters().add(inv.getCurrentStockMeters()));
                target.setEntryDate(inv.getEntryDate() != null ? inv.getEntryDate() : LocalDate.now());
                target.setEnteredBy(currentUser); warehouseRepo.save(target);
                return "📦 发现相同(型号+编号)带坯，库存数量已自动合并追加！";
            } else {
                inv.setTapeNumber(finalNum); inv.setEnteredBy(currentUser);
                if(inv.getEntryDate() == null) inv.setEntryDate(LocalDate.now());
                warehouseRepo.save(inv); return "📦 成功建立全新(型号+编号)库存档案卡片！";
            }
        }
    }

    @Autowired
    private ProductProcessRepo processRepo;

    public List<ProductProcess> getAllProcesses() { return processRepo.findAll(); }

    @Transactional
    public String saveOrUpdateProcess(ProductProcess proc, String currentUser) {
        if (proc.getId() != null) {
            ProductProcess existing = processRepo.findById(proc.getId()).orElse(new ProductProcess());
            existing.setFinishedPartNumber(proc.getFinishedPartNumber().trim());
            existing.setTapePartNumber(proc.getTapePartNumber().trim());
            existing.setWarpSpec(proc.getWarpSpec());
            existing.setWeftSpec(proc.getWeftSpec());
            existing.setEnteredBy(currentUser);
            processRepo.save(existing);
            return "✅ 工艺路线修正成功！";
        } else {
            proc.setEnteredBy(currentUser);
            processRepo.save(proc);
            return "✅ 全新成品工艺BOM路线建立成功！";
        }
    }

    @Transactional
    public String deleteProcess(Integer id) { processRepo.deleteById(id); return "🗑️ 该工艺BOM链条已解除！"; }

    @Transactional
    public String deleteInventory(Integer id) { warehouseRepo.deleteById(id); return "⚠️ 数据条目已从物理磁盘抹除！"; }

}