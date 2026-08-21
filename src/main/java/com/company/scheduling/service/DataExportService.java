package com.company.scheduling.service;

import com.company.scheduling.domain.CoexDailyLog;
import com.company.scheduling.domain.InventoryReconciliation;
import com.company.scheduling.domain.VirtualWarehouse;
import com.company.scheduling.repository.CoexDailyLogRepo;
import com.company.scheduling.repository.InventoryReconciliationRepo;
import com.company.scheduling.repository.VirtualWarehouseRepo;
import com.company.scheduling.repository.WeavingDailyLogRepo;
import com.company.scheduling.domain.WeavingDailyLog;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据导出服务（织造/共挤/库存差值报表 → Excel）
 */
@Service
public class DataExportService {

    @Autowired
    private WeavingDailyLogRepo weavingRepo;

    @Autowired
    private CoexDailyLogRepo coexRepo;

    @Autowired
    private VirtualWarehouseRepo warehouseRepo;

    @Autowired
    private InventoryReconciliationRepo reconciliationRepo;

    /**
     * 导出织造数据为Excel
     * 列: 零件号|年|月|日|机台号|带坯编号|型号规格|经线|纬线|班次|姓名|当班产量|标准产能|标准小时|标准小时产能|绩效工时|备注
     *     |经线米重|纬线米重（2000D）|纬线米重（3000D）|经线耗用kg/m|纬线耗用kg/m(2000D)|纬线耗用kg/m(3000D)|数据质量
     * 前23列与源文件 25-26织造数据.xlsx Sheet1 表头一致，末列数据质量为系统附加列
     *
     * @param year 导出年份（4位）；null 表示导出全部（兼容旧行为，数据量大时建议按年导出）
     */
    public byte[] exportWeavingToExcel(Integer year) {
        List<WeavingDailyLog> logs = year != null
                ? weavingRepo.findByEntryYearOrderByEntryDateDescIdDesc(year)
                : weavingRepo.findAll();
        String[] headers = {"零件号", "年", "月", "日", "机台号", "带坯编号", "型号规格", "经线", "纬线",
                "班次", "姓名", "当班产量", "标准产能", "标准小时", "标准小时产能", "绩效工时", "备注",
                "经线米重", "纬线米重（2000D）", "纬线米重（3000D）",
                "经线耗用kg/m", "纬线耗用kg/m(2000D)", "纬线耗用kg/m(3000D)", "数据质量"};

        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("织造车间台账");
            writeHeader(workbook, sheet, headers);

            int rowIdx = 1;
            for (WeavingDailyLog log : logs) {
                Row row = sheet.createRow(rowIdx++);
                setCellValue(row.createCell(0), log.getPartNumber());
                if (log.getEntryYear() != null) row.createCell(1).setCellValue(log.getEntryYear());
                if (log.getEntryMonth() != null) row.createCell(2).setCellValue(log.getEntryMonth());
                if (log.getEntryDay() != null) row.createCell(3).setCellValue(log.getEntryDay());
                if (log.getMachineNo() != null) row.createCell(4).setCellValue(log.getMachineNo());
                setCellValue(row.createCell(5), log.getTapeCode());
                setCellValue(row.createCell(6), log.getModelSpec());
                setCellValue(row.createCell(7), log.getWarpThread());
                setCellValue(row.createCell(8), log.getWeftThread());
                setCellValue(row.createCell(9), log.getShiftType());
                setCellValue(row.createCell(10), log.getWorkerName());
                setNumericCell(row, 11, log.getShiftOutput());
                setNumericCell(row, 12, log.getStandardCapacity());
                setNumericCell(row, 13, log.getStandardHours());
                setNumericCell(row, 14, log.getStandardHourCapacity());
                setNumericCell(row, 15, log.getPerformanceHours());
                setCellValue(row.createCell(16), log.getRemark());
                setNumericCell(row, 17, log.getWarpWeightPerMeter());
                setNumericCell(row, 18, log.getWeftWeightPerMeter2000D());
                setNumericCell(row, 19, log.getWeftWeightPerMeter3000D());
                setNumericCell(row, 20, log.getWarpUsageKgPerMeter());
                setNumericCell(row, 21, log.getWeftUsageKgPerMeter2000D());
                setNumericCell(row, 22, log.getWeftUsageKgPerMeter3000D());
                setCellValue(row.createCell(23), log.getDataQualityFlag());
            }
            for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);
            workbook.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("织造数据导出失败: " + e.getMessage(), e);
        }
    }

    /**
     * 导出共挤数据为Excel
     * 列: 时间|机台号|产品类型|产品型号|颜色|主材|成品数量|重量|产能|漏胶|数据质量
     *
     * @param year 导出年份（4位）；null 表示导出全部（兼容旧行为，数据量大时建议按年导出）
     */
    public byte[] exportCoexToExcel(Integer year) {
        List<CoexDailyLog> logs = year != null
                ? coexRepo.findByLogDateBetweenOrderByLogDateDescIdDesc(
                        LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))
                : coexRepo.findAll();
        String[] headers = {"时间", "机台号", "产品类型", "产品型号", "颜色", "主材",
                "成品数量", "重量", "产能", "漏胶", "数据质量"};

        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("共挤车间产能明细");
            writeHeader(workbook, sheet, headers);

            int rowIdx = 1;
            for (CoexDailyLog log : logs) {
                Row row = sheet.createRow(rowIdx++);
                setCellValue(row.createCell(0), log.getLogDate() != null ? log.getLogDate().toString() : "");
                setCellValue(row.createCell(1), log.getMachineNo());
                setCellValue(row.createCell(2), log.getProductType());
                setCellValue(row.createCell(3), log.getProductModel());
                setCellValue(row.createCell(4), log.getColor());
                setCellValue(row.createCell(5), log.getMainMaterial());
                if (log.getFinishedQty() != null) row.createCell(6).setCellValue(log.getFinishedQty());
                setNumericCell(row, 7, log.getWeightKg());
                setNumericCell(row, 8, log.getCapacityMeters());
                setNumericCell(row, 9, log.getLeakageKg());
                setCellValue(row.createCell(10), log.getDataQualityFlag());
            }
            for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);
            workbook.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("共挤数据导出失败: " + e.getMessage(), e);
        }
    }

    /**
     * 导出库存数据（含差值报表）
     * 列: 零件号|型号规格|经线|纬线|带坯编号|库存数量|库存类型|备注|DB计算值|差值|核对状态
     *
     * @param snapshotDate 快照日期
     */
    public byte[] exportInventoryWithReconciliation(LocalDate snapshotDate) {
        List<VirtualWarehouse> stocks = snapshotDate != null
                ? warehouseRepo.findBySnapshotDate(snapshotDate)
                : warehouseRepo.findLatestSnapshot();
        Map<String, InventoryReconciliation> reconciliationMap = new HashMap<>();
        LocalDate effectiveDate = snapshotDate;
        if (effectiveDate == null && !stocks.isEmpty()) {
            effectiveDate = stocks.get(0).getSnapshotDate();
        }
        if (effectiveDate != null) {
            for (InventoryReconciliation r : reconciliationRepo.findBySnapshotDate(effectiveDate)) {
                reconciliationMap.put(r.getPartNumber() + "|" + r.getTapeCode(), r);
            }
        }

        String[] headers = {"零件号", "型号规格", "经线", "纬线", "带坯编号", "库存数量",
                "库存类型", "备注", "DB计算值", "差值", "核对状态"};

        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("库存差值报表");
            writeHeader(workbook, sheet, headers);

            int rowIdx = 1;
            for (VirtualWarehouse vw : stocks) {
                Row row = sheet.createRow(rowIdx++);
                setCellValue(row.createCell(0), vw.getPartNumber());
                setCellValue(row.createCell(1), vw.getModelSpec());
                setCellValue(row.createCell(2), vw.getWarpThread());
                setCellValue(row.createCell(3), vw.getWeftThread());
                setCellValue(row.createCell(4), vw.getTapeCode());
                setNumericCell(row, 5, vw.getStockMeters());
                setCellValue(row.createCell(6), vw.getStockType());
                setCellValue(row.createCell(7), vw.getRemark());
                InventoryReconciliation r = reconciliationMap.get(vw.getPartNumber() + "|" + vw.getTapeCode());
                if (r != null) {
                    setNumericCell(row, 8, r.getDbCalculatedValue());
                    setNumericCell(row, 9, r.getDifference());
                    setCellValue(row.createCell(10), r.getReconcileStatus());
                } else {
                    setCellValue(row.createCell(10), vw.getReconcileStatus());
                }
            }
            for (int i = 0; i < headers.length; i++) sheet.autoSizeColumn(i);
            workbook.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("库存数据导出失败: " + e.getMessage(), e);
        }
    }

    // =========================================================================
    // 辅助方法
    // =========================================================================

    private void writeHeader(Workbook workbook, Sheet sheet, String[] headers) {
        Row headerRow = sheet.createRow(0);
        CellStyle headerStyle = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        headerStyle.setFont(font);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }
    }

    private void setCellValue(Cell cell, String value) {
        cell.setCellValue(value != null ? value : "");
    }

    private void setNumericCell(Row row, int colIdx, BigDecimal value) {
        if (value != null) row.createCell(colIdx).setCellValue(value.doubleValue());
    }
}
