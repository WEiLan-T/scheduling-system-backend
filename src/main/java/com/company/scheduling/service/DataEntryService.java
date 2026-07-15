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

        log.setTapePartNumber(req.getTapePartNumber());
        log.setTapeNumber(req.getTapeNumber());
        log.setMachineId(req.getMachineId());
        log.setCapacityPerDay(req.getCapacityPerDay());
        log.setIsDataNormal(req.getIsDataNormal());
        log.setRemarks(req.getRemarks());
        log.setEntryDate(req.getEntryDate());
        log.setTotalDemand(req.getTotalDemand());
        log.setEnteredBy(currentUser);
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

        log.setFinishedPartNumber(req.getFinishedPartNumber());
        log.setOrderNumber(req.getOrderNumber());
        log.setSemiFinishedNumber(req.getSemiFinishedNumber());
        log.setFinishedModelSpec(req.getFinishedModelSpec());
        log.setProductionSpeed(req.getProductionSpeed());
        log.setLineId(req.getLineId());
        log.setCapacityPerDay(req.getCapacityPerDay());
        log.setIsDataNormal(req.getIsDataNormal());
        log.setRemarks(req.getRemarks());
        log.setTapeDemandQty(req.getTapeDemandQty());
        log.setTapePartNumber(req.getTapePartNumber());
        log.setTapeNumber(req.getTapeNumber());
        log.setEntryDate(req.getEntryDate());
        log.setEnteredBy(currentUser);
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
// 🧶 织造车间 MES：Excel 导入与导出引擎（支持空数据、平补库存）
// =========================================================================
    // =========================================================================
    // 🌟 安全的浮点数转换工具（防止 Excel 出现 #DIV/0! 或异常文本导致系统崩溃）
    // =========================================================================
    private BigDecimal parseBigDecimalSafely(String str) {
        if (str == null || str.trim().isEmpty()) return null;
        try {
            return new BigDecimal(str.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 导入织造台账 Excel（完美契合 17 列格式，全字段存入数据库）
     */
    @Transactional
    public String importWeavingExcel(MultipartFile file, String currentUser) throws Exception {
        if (file == null || file.isEmpty()) throw new RuntimeException("上传的织造 Excel 为空！");
        int successCount = 0;
        int skipCount = 0;

        try (InputStream is = file.getInputStream(); Workbook workbook = WorkbookFactory.create(is)) {
            Sheet sheet = workbook.getSheetAt(0);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                String tapePartNumber = getCellValueAsString(row.getCell(0));
                String yearStr = getCellValueAsString(row.getCell(1));
                String monthStr = getCellValueAsString(row.getCell(2));
                String dayStr = getCellValueAsString(row.getCell(3));

                // 核心字段为空则视为无效行
                if (tapePartNumber.isEmpty() || yearStr.isEmpty() || monthStr.isEmpty() || dayStr.isEmpty()) {
                    skipCount++; continue;
                }

                LocalDate entryDate;
                try {
                    int y = (int) Double.parseDouble(yearStr);
                    if (y < 2000) y += 2000;
                    int m = (int) Double.parseDouble(monthStr);
                    int d = (int) Double.parseDouble(dayStr);
                    entryDate = LocalDate.of(y, m, d);
                } catch (Exception e) {
                    skipCount++; continue;
                }

                String machineIdStr = getCellValueAsString(row.getCell(4));
                String tapeNumber = getCellValueAsString(row.getCell(5));
                String finalTapeNum = tapeNumber.isEmpty() ? "DEFAULT" : tapeNumber;

                BigDecimal capacity = parseBigDecimalSafely(getCellValueAsString(row.getCell(11)));
                if (capacity == null) capacity = BigDecimal.ZERO;

                WeavingDailyLog log = new WeavingDailyLog();
                log.setEntryDate(entryDate);
                log.setTapePartNumber(tapePartNumber);
                log.setTapeNumber(finalTapeNum);
                log.setMachineId(machineIdStr.isEmpty() ? "未定" : machineIdStr);
                log.setCapacityPerDay(capacity);
                log.setWorkshopId("织造车间");

                // 🌟 将原本塞进备注的独立字段，分别独立落库
                log.setModelSpec(getCellValueAsString(row.getCell(6)));
                log.setWarpSpec(getCellValueAsString(row.getCell(7)));
                log.setWeftSpec(getCellValueAsString(row.getCell(8)));
                log.setShift(getCellValueAsString(row.getCell(9)));
                log.setOperatorName(getCellValueAsString(row.getCell(10)));

                log.setStandardCapacity(parseBigDecimalSafely(getCellValueAsString(row.getCell(12))));
                log.setStandardHours(parseBigDecimalSafely(getCellValueAsString(row.getCell(13))));
                log.setStandardHourlyCapacity(parseBigDecimalSafely(getCellValueAsString(row.getCell(14))));
                log.setPerformanceHours(parseBigDecimalSafely(getCellValueAsString(row.getCell(15))));

                log.setRemarks(getCellValueAsString(row.getCell(16)));
                log.setIsDataNormal(true);
                log.setEnteredBy(currentUser);

                weavingLogRepo.save(log);
                updateVirtualWarehouse(tapePartNumber, finalTapeNum, capacity, entryDate, currentUser);
                successCount++;
            }
        }
        return "🧶 织造 Excel 解析完毕！已实现 17 列明细全数落库。导入 " + successCount + " 条，跳过 " + skipCount + " 条。";
    }

    /**
     * 导出织造台账到 Excel（严格还原车间 17 列明细格式）
     */
    public byte[] exportWeavingToExcel() throws Exception {
        List<WeavingDailyLog> logs = weavingLogRepo.findAllByOrderByEntryDateDesc();
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("织造车间历史台账");
            Row headerRow = sheet.createRow(0);

            // 🌟 严格对应这 17 个列
            String[] headers = {"零件号", "年", "月", "日", "机台号", "带坯编号", "型号规格", "经线", "纬线", "班次", "姓名", "当班产量", "标准产能", "标准小时", "标准小时产能", "绩效工时", "备注"};

            CellStyle headerStyle = workbook.createCellStyle();
            Font font = workbook.createFont(); font.setBold(true); headerStyle.setFont(font);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            int rowIdx = 1;
            for (WeavingDailyLog log : logs) {
                Row row = sheet.createRow(rowIdx++);

                row.createCell(0).setCellValue(log.getTapePartNumber() != null ? log.getTapePartNumber() : "");

                if (log.getEntryDate() != null) {
                    row.createCell(1).setCellValue(log.getEntryDate().getYear());
                    row.createCell(2).setCellValue(log.getEntryDate().getMonthValue());
                    row.createCell(3).setCellValue(log.getEntryDate().getDayOfMonth());
                }

                row.createCell(4).setCellValue(log.getMachineId() != null ? log.getMachineId() : "");
                row.createCell(5).setCellValue(log.getTapeNumber() != null ? log.getTapeNumber() : "");

                // 🌟 从数据库对应字段原封不动拉取数据
                row.createCell(6).setCellValue(log.getModelSpec() != null ? log.getModelSpec() : "");
                row.createCell(7).setCellValue(log.getWarpSpec() != null ? log.getWarpSpec() : "");
                row.createCell(8).setCellValue(log.getWeftSpec() != null ? log.getWeftSpec() : "");
                row.createCell(9).setCellValue(log.getShift() != null ? log.getShift() : "");
                row.createCell(10).setCellValue(log.getOperatorName() != null ? log.getOperatorName() : "");
                row.createCell(11).setCellValue(log.getCapacityPerDay() != null ? log.getCapacityPerDay().doubleValue() : 0.0);

                // 处理可能为空的 BigDecimal 绩效字段
                if (log.getStandardCapacity() != null) row.createCell(12).setCellValue(log.getStandardCapacity().doubleValue());
                else row.createCell(12).setCellValue("");

                if (log.getStandardHours() != null) row.createCell(13).setCellValue(log.getStandardHours().doubleValue());
                else row.createCell(13).setCellValue("");

                if (log.getStandardHourlyCapacity() != null) row.createCell(14).setCellValue(log.getStandardHourlyCapacity().doubleValue());
                else row.createCell(14).setCellValue("");

                if (log.getPerformanceHours() != null) row.createCell(15).setCellValue(log.getPerformanceHours().doubleValue());
                else row.createCell(15).setCellValue("");

                row.createCell(16).setCellValue(log.getRemarks() != null ? log.getRemarks() : "");
            }
            workbook.write(bos); return bos.toByteArray();
        }
    }

// =========================================================================
// 🗜️ 共挤车间 MES：Excel 导入与导出引擎（支持自动反查型号、扣减库存）
// =========================================================================

    /**
     * 导入共挤台账 Excel（完美契合 13 列结构，包含半成品编号）
     */
    @Transactional
    public String importCoexExcel(MultipartFile file, String currentUser) throws Exception {
        if (file == null || file.isEmpty()) throw new RuntimeException("上传的共挤 Excel 为空！");
        int successCount = 0; int skipCount = 0;

        try (InputStream is = file.getInputStream(); Workbook workbook = WorkbookFactory.create(is)) {
            Sheet sheet = workbook.getSheetAt(0);

            for (int i = 3; i <= sheet.getLastRowNum(); i++) { // 第4行开始是数据
                Row row = sheet.getRow(i); if (row == null) continue;

                String orderNumber = getCellValueAsString(row.getCell(0));
                String lineId = getCellValueAsString(row.getCell(1));
                String finishedPartNumber = getCellValueAsString(row.getCell(2));
                String semiFinishedNumber = getCellValueAsString(row.getCell(3)); // 第4列：半成品编号
                String finishedModelSpec = getCellValueAsString(row.getCell(4));
                String tapeNumber = getCellValueAsString(row.getCell(5));
                String speedStr = getCellValueAsString(row.getCell(6));
                String yearStr = getCellValueAsString(row.getCell(7));
                String monthStr = getCellValueAsString(row.getCell(8));
                String dayStr = getCellValueAsString(row.getCell(9));

                if (finishedPartNumber.isEmpty() || lineId.isEmpty() || yearStr.isEmpty()) { skipCount++; continue; }

                LocalDate entryDate;
                try {
                    int y = (int) Double.parseDouble(yearStr); if (y < 2000) y += 2000;
                    int m = (int) Double.parseDouble(monthStr);
                    int d = (int) Double.parseDouble(dayStr);
                    entryDate = LocalDate.of(y, m, d);
                } catch (Exception e) { skipCount++; continue; }

                BigDecimal speed = parseBigDecimalSafely(speedStr);
                BigDecimal capacity = parseBigDecimalSafely(getCellValueAsString(row.getCell(10)));
                if (capacity == null) capacity = BigDecimal.ZERO;
                BigDecimal demandQty = parseBigDecimalSafely(getCellValueAsString(row.getCell(11)));
                if (demandQty == null) demandQty = BigDecimal.ZERO;

                String tapePartNumber = "DEFAULT";
                Optional<VirtualWarehouse> vw = warehouseRepo.findFirstByTapeNumber(tapeNumber.trim());
                if (vw.isPresent()) tapePartNumber = vw.get().getTapePartNumber();

                CoexDailyLog log = new CoexDailyLog();
                log.setEntryDate(entryDate); log.setOrderNumber(orderNumber); log.setLineId(lineId);
                log.setFinishedPartNumber(finishedPartNumber); log.setSemiFinishedNumber(semiFinishedNumber);
                log.setFinishedModelSpec(finishedModelSpec); log.setTapeNumber(tapeNumber);
                log.setProductionSpeed(speed); log.setCapacityPerDay(capacity);
                log.setTapePartNumber(tapePartNumber); log.setTapeDemandQty(demandQty);
                log.setRemarks(getCellValueAsString(row.getCell(12)));
                log.setWorkshopId("共挤车间"); log.setIsDataNormal(true); log.setEnteredBy(currentUser);
                coexLogRepo.save(log);

                if (demandQty.compareTo(BigDecimal.ZERO) > 0) {
                    updateVirtualWarehouse(tapePartNumber, tapeNumber, demandQty.negate(), entryDate, currentUser);
                }
                successCount++;
            }
        }
        return "🗜️ 共挤 Excel 解析完毕！全列明细已落库。导入成功 " + successCount + " 条，跳过 " + skipCount + " 条。";
    }

    /**
     * 导出全量共挤台账到 Excel（严格还原带双层表头和合并单元格的 13 列格式）
     */
    public byte[] exportCoexToExcel() throws Exception {
        List<CoexDailyLog> logs = coexLogRepo.findAllByOrderByEntryDateDesc();
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("共挤车间产线产能");

            CellStyle headerStyle = workbook.createCellStyle();
            Font font = workbook.createFont(); font.setBold(true); headerStyle.setFont(font);
            headerStyle.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER);
            headerStyle.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);

            // 第 1 行：大标题
            Row row0 = sheet.createRow(0); Cell titleCell = row0.createCell(0);
            titleCell.setCellValue("共挤产线产能明细汇总"); titleCell.setCellStyle(headerStyle);
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, 12));

            // 第 2 行：主表头
            Row row1 = sheet.createRow(1);
            String[] headers1 = {"订单号", "产线号", "成品零件号", "半成品编号", "成品规格型号", "带坯编号", "生产速度(m/s)", "共挤日期", "", "", "共挤成品长度", "带坯消耗长度", "备注"};
            for (int i = 0; i < headers1.length; i++) {
                Cell c = row1.createCell(i); c.setCellValue(headers1[i]); c.setCellStyle(headerStyle);
            }

            // 第 3 行：日期副表头
            Row row2 = sheet.createRow(2);
            row2.createCell(7).setCellValue("年"); row2.getCell(7).setCellStyle(headerStyle);
            row2.createCell(8).setCellValue("月"); row2.getCell(8).setCellStyle(headerStyle);
            row2.createCell(9).setCellValue("日"); row2.getCell(9).setCellStyle(headerStyle);

            // 合并单元格
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(1, 1, 7, 9)); // 合并“共挤日期”
            int[] mergeCols = {0, 1, 2, 3, 4, 5, 6, 10, 11, 12};
            for (int colIdx : mergeCols) sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(1, 2, colIdx, colIdx));

            // 写入数据
            int rowIdx = 3;
            for (CoexDailyLog log : logs) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(log.getOrderNumber() != null ? log.getOrderNumber() : "");
                row.createCell(1).setCellValue(log.getLineId() != null ? log.getLineId() : "");
                row.createCell(2).setCellValue(log.getFinishedPartNumber() != null ? log.getFinishedPartNumber() : "");
                row.createCell(3).setCellValue(log.getSemiFinishedNumber() != null ? log.getSemiFinishedNumber() : "");
                row.createCell(4).setCellValue(log.getFinishedModelSpec() != null ? log.getFinishedModelSpec() : "");
                row.createCell(5).setCellValue(log.getTapeNumber() != null ? log.getTapeNumber() : "");

                if (log.getProductionSpeed() != null) row.createCell(6).setCellValue(log.getProductionSpeed().doubleValue());
                else row.createCell(6).setCellValue("");

                if (log.getEntryDate() != null) {
                    row.createCell(7).setCellValue(log.getEntryDate().getYear());
                    row.createCell(8).setCellValue(log.getEntryDate().getMonthValue());
                    row.createCell(9).setCellValue(log.getEntryDate().getDayOfMonth());
                }

                if (log.getCapacityPerDay() != null) row.createCell(10).setCellValue(log.getCapacityPerDay().doubleValue());
                else row.createCell(10).setCellValue("");

                if (log.getTapeDemandQty() != null) row.createCell(11).setCellValue(log.getTapeDemandQty().doubleValue());
                else row.createCell(11).setCellValue("");

                row.createCell(12).setCellValue(log.getRemarks() != null ? log.getRemarks() : "");
            }
            workbook.write(bos); return bos.toByteArray();
        }
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

                // 🌟 更新后的 6 列安全读取
                String finishedPartNumber = getCellValueAsString(row.getCell(0));
                String finishedModelSpec = getCellValueAsString(row.getCell(1));
                String tapePartNumber = getCellValueAsString(row.getCell(2));
                String tapeModelSpec = getCellValueAsString(row.getCell(3));
                String warpSpec = getCellValueAsString(row.getCell(4));
                String weftSpec = getCellValueAsString(row.getCell(5));

                if (finishedPartNumber.isEmpty()) { skipCount++; continue; }

                ProductProcess proc = processRepo.findByFinishedPartNumber(finishedPartNumber).orElse(new ProductProcess());
                proc.setFinishedPartNumber(finishedPartNumber);
                proc.setFinishedModelSpec(finishedModelSpec); // 👈 存入规格
                proc.setTapePartNumber(tapePartNumber.isEmpty() ? "DEFAULT" : tapePartNumber);
                proc.setTapeModelSpec(tapeModelSpec);         // 👈 存入规格
                proc.setWarpSpec(warpSpec);
                proc.setWeftSpec(weftSpec);
                proc.setEnteredBy(currentUser);
                processRepo.save(proc);

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
            String[] headers = {"成品零件号", "成品规格型号", "带坯零件号", "带坯规格型号", "经线型号", "纬线型号"};

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
                row.createCell(0).setCellValue(proc.getFinishedPartNumber() != null ? proc.getFinishedPartNumber() : "");
                row.createCell(1).setCellValue(proc.getFinishedModelSpec() != null ? proc.getFinishedModelSpec() : "");
                row.createCell(2).setCellValue(proc.getTapePartNumber() != null ? proc.getTapePartNumber() : "");
                row.createCell(3).setCellValue(proc.getTapeModelSpec() != null ? proc.getTapeModelSpec() : "");
                row.createCell(4).setCellValue(proc.getWarpSpec() != null ? proc.getWarpSpec() : "");
                row.createCell(5).setCellValue(proc.getWeftSpec() != null ? proc.getWeftSpec() : "");
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
            // 👇 新增规格的保存
            existing.setFinishedModelSpec(proc.getFinishedModelSpec());
            existing.setTapeModelSpec(proc.getTapeModelSpec());
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