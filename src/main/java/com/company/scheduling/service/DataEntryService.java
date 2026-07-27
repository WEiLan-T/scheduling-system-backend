package com.company.scheduling.service;

import com.company.scheduling.domain.*;
import com.company.scheduling.dto.*;
import com.company.scheduling.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

        log.setModelSpec(req.getModelSpec()); log.setWarpSpec(req.getWarpSpec()); log.setWeftSpec(req.getWeftSpec());
        log.setShift(req.getShift()); log.setOperatorName(req.getOperatorName()); log.setStandardCapacity(req.getStandardCapacity());
        log.setStandardHours(req.getStandardHours()); log.setStandardHourlyCapacity(req.getStandardHourlyCapacity()); log.setPerformanceHours(req.getPerformanceHours());

        weavingLogRepo.save(log);
        return req.getId() != null ? "✅ 织造历史修改成功，库存已同步！" : "✅ 今日织造归档成功！";
    }

    @Transactional
    public String deleteWeavingLog(Integer id, String currentUser) {
        WeavingDailyLog log = weavingLogRepo.findById(id).orElseThrow(() -> new RuntimeException("台账不存在"));
        if (log.getCapacityPerDay() != null && log.getCapacityPerDay().compareTo(BigDecimal.ZERO) > 0) {
            updateVirtualWarehouse(log.getTapePartNumber(), log.getTapeNumber(), log.getCapacityPerDay().negate(), LocalDate.now(), currentUser);
        }
        weavingLogRepo.deleteById(id);
        return "🗑️ 织造台账已被物理废弃，带坯库存已扣减还原！";
    }

    @Transactional
    public String recordCoexData(CoexEntryRequest req, String currentUser) {
        if (req.getTapePartNumber() == null || req.getTapePartNumber().trim().isEmpty()) {
            if (req.getTapeNumber() == null || req.getTapeNumber().trim().isEmpty()) throw new RuntimeException("操作失败：必须提供带坯物理编号！");
            VirtualWarehouse vw = warehouseRepo.findFirstByTapeNumber(req.getTapeNumber().trim()).orElseThrow(() -> new RuntimeException("找不到带坯卷号！"));
            req.setTapePartNumber(vw.getTapePartNumber());
        }
        CoexLineStatus status = coexStatusRepo.findById(req.getLineId()).orElse(new CoexLineStatus());
        status.setLineId(req.getLineId()); status.setWorkshopId(req.getWorkshopId());
        status.setCaliberLimit(req.getCaliberLimit()); status.setLineStatus(req.getLineStatus()); status.setEnteredBy(currentUser);
        coexStatusRepo.save(status);

        CoexDailyLog log;
        if (req.getId() != null) {
            log = coexLogRepo.findById(req.getId()).orElseThrow(() -> new RuntimeException("台账不存在！"));
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

        log.setFinishedPartNumber(req.getFinishedPartNumber()); log.setOrderNumber(req.getOrderNumber());
        log.setSemiFinishedNumber(req.getSemiFinishedNumber()); log.setFinishedModelSpec(req.getFinishedModelSpec());
        log.setProductionSpeed(req.getProductionSpeed()); log.setLineId(req.getLineId()); log.setCapacityPerDay(req.getCapacityPerDay());
        log.setIsDataNormal(req.getIsDataNormal()); log.setRemarks(req.getRemarks()); log.setTapeDemandQty(req.getTapeDemandQty());
        log.setTapePartNumber(req.getTapePartNumber()); log.setTapeNumber(req.getTapeNumber()); log.setEntryDate(req.getEntryDate()); log.setEnteredBy(currentUser);
        coexLogRepo.save(log);

        return req.getId() != null ? "✅ 共挤台账修改成功！" : "✅ 共挤台账归档成功，库存已扣除。";
    }

    @Transactional
    public String deleteCoexLog(Integer id, String currentUser) {
        CoexDailyLog log = coexLogRepo.findById(id).orElseThrow(() -> new RuntimeException("台账不存在"));
        if (log.getTapeDemandQty() != null && log.getTapeDemandQty().compareTo(BigDecimal.ZERO) > 0) {
            updateVirtualWarehouse(log.getTapePartNumber(), log.getTapeNumber(), log.getTapeDemandQty(), LocalDate.now(), currentUser);
        }
        coexLogRepo.deleteById(id);
        return "🗑️ 共挤台账已废弃，退还对应库存！";
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

    private BigDecimal parseBigDecimalSafely(String str) {
        if (str == null || str.trim().isEmpty()) return null;
        try { return new BigDecimal(str.trim()); } catch (Exception e) { return null; }
    }

    private LocalDate parseDateSafely(Cell cell, String yStr, String mStr, String dStr) {
        if (cell != null) {
            try {
                if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                }
                String str = getCellValueAsString(cell);
                if (!str.isEmpty()) {
                    if (str.contains(" ")) str = str.split(" ")[0];
                    if (str.contains("/")) str = str.replace("/", "-");
                    return LocalDate.parse(str);
                }
            } catch (Exception ignored) {}
        }
        try {
            int y = (int) Double.parseDouble(yStr); if (y < 2000) y += 2000;
            int m = (int) Double.parseDouble(mStr); int d = (int) Double.parseDouble(dStr);
            return LocalDate.of(y, m, d);
        } catch (Exception e) { return null; }
    }

    // =========================================================================
    // 🧶 织造车间 Excel 导入 (智能嗅探 + 双重去重)
    // =========================================================================
    @Transactional
    public String importWeavingExcel(MultipartFile file, String currentUser) throws Exception {
        if (file == null || file.isEmpty()) throw new RuntimeException("文件为空！");
        int successCount = 0; int skipCount = 0;
        Set<String> seenKeys = new HashSet<>();

        try (InputStream is = file.getInputStream(); Workbook workbook = WorkbookFactory.create(is)) {
            Sheet sheet = workbook.getSheetAt(0);

            Row headerRow = null; int headerIdx = 0;
            for(int r = 0; r <= 5; r++) {
                Row row = sheet.getRow(r);
                if(row != null) {
                    for(int c=0; c<row.getLastCellNum(); c++) {
                        String val = getCellValueAsString(row.getCell(c));
                        if(val.contains("零件") || val.contains("带坯") || val.contains("机台")) { headerRow = row; headerIdx = r; break; }
                    }
                }
                if(headerRow != null) break;
            }
            if(headerRow == null) headerRow = sheet.getRow(0);

            int colDate=-1, colY=-1, colM=-1, colD=-1, colPn=-1, colTapeNum=-1, colMach=-1, colModel=-1;
            int colWarp=-1, colWeft=-1, colShift=-1, colOp=-1, colCap=-1, colStdCap=-1, colStdHrs=-1, colStdHrCap=-1, colPerf=-1, colRemarks=-1;

            for(int j=0; j<headerRow.getLastCellNum(); j++) {
                String h = getCellValueAsString(headerRow.getCell(j)).trim().replaceAll("\\s+", "");
                if(h.isEmpty()) continue;
                if(h.equals("零件号") || h.equals("带坯零件号") || h.contains("产品编号")) colPn = j;
                else if(h.equals("年")) colY = j; else if(h.equals("月")) colM = j; else if(h.equals("日")) colD = j;
                else if(h.contains("日期") || h.contains("时间") || h.contains("账期")) colDate = j;
                else if(h.contains("机台")) colMach = j;
                else if(h.contains("编号") || h.contains("卷号")) colTapeNum = j;
                else if(h.contains("型号") || h.contains("规格")) colModel = j;
                else if(h.contains("经线")) colWarp = j; else if(h.contains("纬线")) colWeft = j;
                else if(h.contains("班次")) colShift = j; else if(h.contains("姓名") || h.contains("操作工")) colOp = j;
                else if(h.contains("当班产量") || (h.contains("产量") && !h.contains("标准") && !h.contains("小时"))) colCap = j;
                else if(h.contains("标准产能")) colStdCap = j; else if(h.contains("标准工时") || (h.contains("标准小时") && !h.contains("产能"))) colStdHrs = j;
                else if(h.contains("小时产能")) colStdHrCap = j; else if(h.contains("绩效")) colPerf = j; else if(h.contains("备注")) colRemarks = j;
            }

            for (int i = headerIdx + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i); if (row == null) continue;

                String tapePartNumber = colPn >= 0 ? getCellValueAsString(row.getCell(colPn)) : "";
                String machineIdStr = colMach >= 0 ? getCellValueAsString(row.getCell(colMach)) : "未定";
                String shift = colShift >= 0 ? getCellValueAsString(row.getCell(colShift)) : "白班";
                String tapeNumber = colTapeNum >= 0 ? getCellValueAsString(row.getCell(colTapeNum)) : "DEFAULT";
                if(tapeNumber.isEmpty()) tapeNumber = "DEFAULT";

                LocalDate entryDate = parseDateSafely(colDate >= 0 ? row.getCell(colDate) : null,
                        colY >= 0 ? getCellValueAsString(row.getCell(colY)) : "",
                        colM >= 0 ? getCellValueAsString(row.getCell(colM)) : "",
                        colD >= 0 ? getCellValueAsString(row.getCell(colD)) : "");

                if (tapePartNumber.isEmpty() || entryDate == null) { skipCount++; continue; }

                // 🌟 双重去重验证
                String uniqueKey = entryDate + "_" + machineIdStr + "_" + tapePartNumber + "_" + shift;
                if(seenKeys.contains(uniqueKey)) { skipCount++; continue; }
                if(weavingLogRepo.existsByEntryDateAndMachineIdAndTapePartNumberAndShift(entryDate, machineIdStr, tapePartNumber, shift)) { skipCount++; continue; }
                seenKeys.add(uniqueKey);

                BigDecimal capacity = colCap >= 0 ? parseBigDecimalSafely(getCellValueAsString(row.getCell(colCap))) : BigDecimal.ZERO;
                if (capacity == null) capacity = BigDecimal.ZERO;

                WeavingDailyLog log = new WeavingDailyLog();
                log.setEntryDate(entryDate); log.setTapePartNumber(tapePartNumber);
                log.setTapeNumber(tapeNumber); log.setMachineId(machineIdStr);
                log.setCapacityPerDay(capacity); log.setWorkshopId("织造车间");

                if(colModel >= 0) log.setModelSpec(getCellValueAsString(row.getCell(colModel)));
                if(colWarp >= 0) log.setWarpSpec(getCellValueAsString(row.getCell(colWarp)));
                if(colWeft >= 0) log.setWeftSpec(getCellValueAsString(row.getCell(colWeft)));
                log.setShift(shift);
                if(colOp >= 0) log.setOperatorName(getCellValueAsString(row.getCell(colOp)));
                if(colStdCap >= 0) log.setStandardCapacity(parseBigDecimalSafely(getCellValueAsString(row.getCell(colStdCap))));
                if(colStdHrs >= 0) log.setStandardHours(parseBigDecimalSafely(getCellValueAsString(row.getCell(colStdHrs))));
                if(colStdHrCap >= 0) log.setStandardHourlyCapacity(parseBigDecimalSafely(getCellValueAsString(row.getCell(colStdHrCap))));
                if(colPerf >= 0) log.setPerformanceHours(parseBigDecimalSafely(getCellValueAsString(row.getCell(colPerf))));
                if(colRemarks >= 0) log.setRemarks(getCellValueAsString(row.getCell(colRemarks)));

                log.setIsDataNormal(true); log.setEnteredBy(currentUser);
                weavingLogRepo.save(log);
                updateVirtualWarehouse(tapePartNumber, tapeNumber, capacity, entryDate, currentUser);
                successCount++;
            }
        }
        return "🧶 织造 Excel 解析完毕！导入 " + successCount + " 条，剔除重复行 " + skipCount + " 条。";
    }

    public byte[] exportWeavingToExcel() throws Exception {
        List<WeavingDailyLog> logs = weavingLogRepo.findAllByOrderByEntryDateDesc();
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("织造车间台账");
            Row headerRow = sheet.createRow(0);
            String[] headers = {"日期", "机台号", "零件号", "带坯编号", "型号规格", "经线", "纬线", "班次", "姓名", "当班产量", "标准产能", "标准小时", "小时产能", "绩效工时", "备注"};

            CellStyle headerStyle = workbook.createCellStyle(); Font font = workbook.createFont(); font.setBold(true); headerStyle.setFont(font);
            for (int i = 0; i < headers.length; i++) { Cell cell = headerRow.createCell(i); cell.setCellValue(headers[i]); cell.setCellStyle(headerStyle); }

            int rowIdx = 1;
            for (WeavingDailyLog log : logs) {
                Row row = sheet.createRow(rowIdx++);
                if (log.getEntryDate() != null) row.createCell(0).setCellValue(log.getEntryDate().toString());
                row.createCell(1).setCellValue(log.getMachineId() != null ? log.getMachineId() : "");
                row.createCell(2).setCellValue(log.getTapePartNumber() != null ? log.getTapePartNumber() : "");
                row.createCell(3).setCellValue(log.getTapeNumber() != null ? log.getTapeNumber() : "");
                row.createCell(4).setCellValue(log.getModelSpec() != null ? log.getModelSpec() : "");
                row.createCell(5).setCellValue(log.getWarpSpec() != null ? log.getWarpSpec() : "");
                row.createCell(6).setCellValue(log.getWeftSpec() != null ? log.getWeftSpec() : "");
                row.createCell(7).setCellValue(log.getShift() != null ? log.getShift() : "");
                row.createCell(8).setCellValue(log.getOperatorName() != null ? log.getOperatorName() : "");
                if(log.getCapacityPerDay() != null) row.createCell(9).setCellValue(log.getCapacityPerDay().doubleValue());
                if(log.getStandardCapacity() != null) row.createCell(10).setCellValue(log.getStandardCapacity().doubleValue());
                if(log.getStandardHours() != null) row.createCell(11).setCellValue(log.getStandardHours().doubleValue());
                if(log.getStandardHourlyCapacity() != null) row.createCell(12).setCellValue(log.getStandardHourlyCapacity().doubleValue());
                if(log.getPerformanceHours() != null) row.createCell(13).setCellValue(log.getPerformanceHours().doubleValue());
                row.createCell(14).setCellValue(log.getRemarks() != null ? log.getRemarks() : "");
            }
            for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);
            workbook.write(bos); return bos.toByteArray();
        }
    }

    // =========================================================================
    // 🗜️ 共挤车间 Excel 导入
    // =========================================================================
    @Transactional
    public String importCoexExcel(MultipartFile file, String currentUser) throws Exception {
        if (file == null || file.isEmpty()) throw new RuntimeException("文件为空！");
        int successCount = 0; int skipCount = 0; Set<String> seenKeys = new HashSet<>();

        try (InputStream is = file.getInputStream(); Workbook workbook = WorkbookFactory.create(is)) {
            Sheet sheet = workbook.getSheetAt(0);

            Row headerRow = null; int headerIdx = 0;
            for(int r = 0; r <= 5; r++) {
                Row row = sheet.getRow(r);
                if(row != null) {
                    for(int c=0; c<row.getLastCellNum(); c++) {
                        String val = getCellValueAsString(row.getCell(c));
                        if(val.contains("订单") || val.contains("成品") || val.contains("线号")) { headerRow = row; headerIdx = r; break; }
                    }
                }
                if(headerRow != null) break;
            }
            if(headerRow == null) headerRow = sheet.getRow(0);

            int colDate=-1, colY=-1, colM=-1, colD=-1, colOrder=-1, colLine=-1, colFinPn=-1, colSemiPn=-1, colTapeNum=-1, colSpeed=-1, colCap=-1, colDemand=-1, colRemarks=-1, colModel=-1;

            for(int j=0; j<headerRow.getLastCellNum(); j++) {
                String h = getCellValueAsString(headerRow.getCell(j)).trim().replaceAll("\\s+", "");
                if(h.isEmpty()) continue;
                if(h.contains("订单")) colOrder = j;
                else if(h.contains("线号") || h.contains("产线")) colLine = j;
                else if(h.contains("成品") || h.contains("管带") || h.equals("零件号")) colFinPn = j;
                else if(h.contains("半成品")) colSemiPn = j;
                else if(h.contains("带坯编号") || h.contains("消耗卷") || h.equals("带坯")) colTapeNum = j;
                else if(h.contains("速度")) colSpeed = j;
                else if(h.contains("产量") || h.contains("产出")) colCap = j;
                else if(h.contains("消耗长度") || h.contains("带坯消耗")) colDemand = j;
                else if(h.contains("型号") || h.contains("规格")) colModel = j;
                else if(h.equals("年")) colY = j; else if(h.equals("月")) colM = j; else if(h.equals("日")) colD = j;
                else if(h.contains("日期") || h.contains("时间") || h.contains("账期")) colDate = j;
                else if(h.contains("备注")) colRemarks = j;
            }

            for (int i = headerIdx + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i); if (row == null) continue;

                String orderNumber = colOrder >= 0 ? getCellValueAsString(row.getCell(colOrder)) : "";
                String lineId = colLine >= 0 ? getCellValueAsString(row.getCell(colLine)) : "未定";
                String finishedPartNumber = colFinPn >= 0 ? getCellValueAsString(row.getCell(colFinPn)) : "";

                LocalDate entryDate = parseDateSafely(colDate >= 0 ? row.getCell(colDate) : null,
                        colY >= 0 ? getCellValueAsString(row.getCell(colY)) : "",
                        colM >= 0 ? getCellValueAsString(row.getCell(colM)) : "",
                        colD >= 0 ? getCellValueAsString(row.getCell(colD)) : "");

                if (finishedPartNumber.isEmpty() || entryDate == null) { skipCount++; continue; }

                String uniqueKey = entryDate + "_" + lineId + "_" + orderNumber + "_" + finishedPartNumber;
                if(seenKeys.contains(uniqueKey)) { skipCount++; continue; }
                if(coexLogRepo.existsByEntryDateAndLineIdAndOrderNumberAndFinishedPartNumber(entryDate, lineId, orderNumber, finishedPartNumber)) { skipCount++; continue; }
                seenKeys.add(uniqueKey);

                String tapeNumber = colTapeNum >= 0 ? getCellValueAsString(row.getCell(colTapeNum)) : "DEFAULT";
                if(tapeNumber.isEmpty()) tapeNumber = "DEFAULT";
                String tapePartNumber = "DEFAULT";
                Optional<VirtualWarehouse> vw = warehouseRepo.findFirstByTapeNumber(tapeNumber.trim());
                if (vw.isPresent()) tapePartNumber = vw.get().getTapePartNumber();

                BigDecimal capacity = colCap >= 0 ? parseBigDecimalSafely(getCellValueAsString(row.getCell(colCap))) : BigDecimal.ZERO;
                if (capacity == null) capacity = BigDecimal.ZERO;
                BigDecimal demandQty = colDemand >= 0 ? parseBigDecimalSafely(getCellValueAsString(row.getCell(colDemand))) : BigDecimal.ZERO;
                if (demandQty == null) demandQty = BigDecimal.ZERO;

                CoexDailyLog log = new CoexDailyLog();
                log.setEntryDate(entryDate); log.setOrderNumber(orderNumber); log.setLineId(lineId);
                log.setFinishedPartNumber(finishedPartNumber);
                if(colSemiPn >= 0) log.setSemiFinishedNumber(getCellValueAsString(row.getCell(colSemiPn)));
                if(colModel >= 0) log.setFinishedModelSpec(getCellValueAsString(row.getCell(colModel)));
                log.setTapeNumber(tapeNumber);
                if(colSpeed >= 0) log.setProductionSpeed(parseBigDecimalSafely(getCellValueAsString(row.getCell(colSpeed))));
                log.setCapacityPerDay(capacity); log.setTapePartNumber(tapePartNumber); log.setTapeDemandQty(demandQty);
                if(colRemarks >= 0) log.setRemarks(getCellValueAsString(row.getCell(colRemarks)));
                log.setWorkshopId("共挤车间"); log.setIsDataNormal(true); log.setEnteredBy(currentUser);

                coexLogRepo.save(log);
                if (demandQty.compareTo(BigDecimal.ZERO) > 0) updateVirtualWarehouse(tapePartNumber, tapeNumber, demandQty.negate(), entryDate, currentUser);
                successCount++;
            }
        }
        return "🗜️ 共挤 Excel 解析完毕！导入 " + successCount + " 条，剔除重复行 " + skipCount + " 条。";
    }

    public byte[] exportCoexToExcel() throws Exception {
        List<CoexDailyLog> logs = coexLogRepo.findAllByOrderByEntryDateDesc();
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("共挤车间产能明细");
            Row headerRow = sheet.createRow(0);
            String[] headers = {"日期", "订单号", "产线号", "成品零件号", "半成品编号", "成品规格", "带坯编号", "速度(m/s)", "产量(m)", "带坯消耗(m)", "备注"};

            CellStyle headerStyle = workbook.createCellStyle(); Font font = workbook.createFont(); font.setBold(true); headerStyle.setFont(font);
            for (int i = 0; i < headers.length; i++) { Cell cell = headerRow.createCell(i); cell.setCellValue(headers[i]); cell.setCellStyle(headerStyle); }

            int rowIdx = 1;
            for (CoexDailyLog log : logs) {
                Row row = sheet.createRow(rowIdx++);
                if (log.getEntryDate() != null) row.createCell(0).setCellValue(log.getEntryDate().toString());
                row.createCell(1).setCellValue(log.getOrderNumber() != null ? log.getOrderNumber() : "");
                row.createCell(2).setCellValue(log.getLineId() != null ? log.getLineId() : "");
                row.createCell(3).setCellValue(log.getFinishedPartNumber() != null ? log.getFinishedPartNumber() : "");
                row.createCell(4).setCellValue(log.getSemiFinishedNumber() != null ? log.getSemiFinishedNumber() : "");
                row.createCell(5).setCellValue(log.getFinishedModelSpec() != null ? log.getFinishedModelSpec() : "");
                row.createCell(6).setCellValue(log.getTapeNumber() != null ? log.getTapeNumber() : "");
                if (log.getProductionSpeed() != null) row.createCell(7).setCellValue(log.getProductionSpeed().doubleValue());
                if (log.getCapacityPerDay() != null) row.createCell(8).setCellValue(log.getCapacityPerDay().doubleValue());
                if (log.getTapeDemandQty() != null) row.createCell(9).setCellValue(log.getTapeDemandQty().doubleValue());
                row.createCell(10).setCellValue(log.getRemarks() != null ? log.getRemarks() : "");
            }
            for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);
            workbook.write(bos); return bos.toByteArray();
        }
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

    private void updateVirtualWarehouse(String tapePartNumber, String tapeNumber, BigDecimal changeMeters, LocalDate entryDate, String currentUser) {
        if (tapePartNumber == null || tapePartNumber.isEmpty() || changeMeters == null) return;
        String finalTapeNum = (tapeNumber == null || tapeNumber.trim().isEmpty()) ? "DEFAULT" : tapeNumber.trim();
        VirtualWarehouse warehouse = warehouseRepo.findByTapePartNumberAndTapeNumber(tapePartNumber, finalTapeNum).orElse(new VirtualWarehouse());
        warehouse.setTapePartNumber(tapePartNumber); warehouse.setTapeNumber(finalTapeNum);
        BigDecimal current = warehouse.getCurrentStockMeters() != null ? warehouse.getCurrentStockMeters() : BigDecimal.ZERO;
        warehouse.setCurrentStockMeters(current.add(changeMeters)); warehouse.setEntryDate(entryDate); warehouse.setEnteredBy(currentUser);
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
    @Transactional public String deleteInventory(Integer id) { warehouseRepo.deleteById(id); return "⚠️ 数据条目已从物理磁盘抹除！"; }
}