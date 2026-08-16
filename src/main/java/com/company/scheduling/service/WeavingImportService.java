package com.company.scheduling.service;

import com.company.scheduling.domain.WeavingDailyLog;
import com.company.scheduling.dto.DataQualityReport;
import com.company.scheduling.dto.ImportResult;
import com.company.scheduling.repository.WeavingDailyLogRepo;
import com.company.scheduling.util.ExcelUtils;
import jakarta.persistence.EntityManager;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 织造车间 Excel 导入服务
 * 流程: 解析Excel → Python清洗(A/B/C分级) → 增量识别(查重) → 批量入库
 */
@Service
public class WeavingImportService {

    private static final Logger log = LoggerFactory.getLogger(WeavingImportService.class);

    /** 批量保存大小（织造单次导入可达6万条） */
    private static final int BATCH_SIZE = 1000;

    @Autowired
    private PythonDataCleaner pythonDataCleaner;

    @Autowired
    private WeavingDailyLogRepo weavingRepo;

    @Autowired
    private EntityManager entityManager;

    /**
     * 导入织造Excel数据
     * 流程: 解析Excel → Python清洗(A/B/C分级) → 增量识别(查重) → 入库
     *
     * @param file Excel文件
     * @return ImportResult 包含新增/跳过/拒绝数量和质量报告
     */
    @Transactional
    public ImportResult importWeavingExcel(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new RuntimeException("文件为空！");

        // 1+2. 定位表头并逐行读取为 List<Map<String,String>>（位置键 col0..col16）
        List<Map<String, String>> rows = readWeavingRows(file);

        // 3. 调用Python清洗（不可用时自动降级到Java内置清洗），获得A/B/C分级报告
        DataQualityReport report = pythonDataCleaner.cleanWeavingData(rows);

        int inserted = 0;
        int skipped = 0;
        // 导入前一次性加载已存在的唯一键集合（年-月-日-机台号-班次-带坯编号-型号规格），
        // 循环内内存判重，避免6万条逐行DB查重；同时兼作文件内去重集合
        Set<String> existingKeys = new HashSet<>(weavingRepo.findAllExistingKeys());
        List<WeavingDailyLog> buffer = new ArrayList<>(BATCH_SIZE);
        // 收集本次实际新增的台账行，供导入完成后异步同步虚拟库存（随 ImportResult 透传）
        List<WeavingDailyLog> insertedLogs = new ArrayList<>();

        // 4. C级数据已被清洗器剔除（不在cleanedData中），直接计入拒绝数
        // 5. A/B级数据进行增量识别后入库
        for (Map<String, Object> record : report.getCleanedData()) {
            WeavingDailyLog entity = mapToEntity(record);
            if (entity == null) {
                skipped++;
                continue;
            }
            // 唯一键格式与 findAllExistingKeys 的 CONCAT 结果保持一致
            String uniqueKey = entity.getEntryYear() + "-" + entity.getEntryMonth() + "-" + entity.getEntryDay()
                    + "-" + entity.getMachineNo() + "-" + entity.getShiftType()
                    + "-" + entity.getTapeCode() + "-" + entity.getModelSpec();
            // 文件内去重 + DB查重（内存判重，add失败即已存在）
            if (!existingKeys.add(uniqueKey)) {
                skipped++;
                continue;
            }
            buffer.add(entity);
            insertedLogs.add(entity);
            inserted++;
            // 6. 分批保存，每批1000条，避免持久化上下文过大
            if (buffer.size() >= BATCH_SIZE) {
                weavingRepo.saveAll(buffer);
                weavingRepo.flush();
                entityManager.clear(); // 清理一级缓存，控制内存占用
                buffer.clear();
            }
        }
        if (!buffer.isEmpty()) {
            weavingRepo.saveAll(buffer);
            weavingRepo.flush();
            entityManager.clear();
            buffer.clear();
        }

        // 7. 构建ImportResult返回
        ImportResult result = new ImportResult();
        result.setTotalRows(report.getTotalRows());
        result.setInsertedCount(inserted);
        result.setSkippedCount(skipped);
        result.setRejectedCount(report.getGradeCCount());
        result.setQualityReport(report);
        result.setImportBatchId("WEAVING-" + System.currentTimeMillis());
        result.setInsertedPayload(insertedLogs);
        result.setMessage("🧶 织造Excel导入完成：新增 " + inserted + " 条，跳过(重复) " + skipped
                + " 条，拒绝(C级) " + report.getGradeCCount() + " 条。");
        log.info(result.getMessage());
        return result;
    }

    /**
     * 重新检查B级数据（手动触发）
     * 对B级记录重新校验：零件号补齐、标准产能/标准小时非空、
     * 标准小时产能与绩效工时一致性(5%容差)通过则升级为A级
     */
    @Transactional
    public Map<String, Object> recheckGradeBRecords() {
        List<WeavingDailyLog> bRecords = weavingRepo.findGradeBRecords();
        int upgraded = 0;
        for (WeavingDailyLog record : bRecords) {
            if (isQualifiedForGradeA(record)) {
                record.setDataQualityFlag("A");
                upgraded++;
            }
        }
        weavingRepo.saveAll(bRecords);

        Map<String, Object> summary = new HashMap<>();
        summary.put("totalGradeB", bRecords.size());
        summary.put("upgradedToA", upgraded);
        summary.put("remainGradeB", bRecords.size() - upgraded);
        return summary;
    }

    // =========================================================================
    // Excel 解析
    // =========================================================================

    /**
     * 定位表头并按清洗器要求的位置键(col0..col22)读取数据行
     * 位置约定: 0零件号|1年|2月|3日|4机台号|5带坯编号|6型号规格|7经线|8纬线
     *          |9班次|10姓名|11当班产量|12标准产能|13标准小时|14标准小时产能|15绩效工时|16备注
     *          |17经线米重|18纬线米重2000D|19纬线米重3000D|20经线耗用|21纬线耗用2000D|22纬线耗用3000D
     */
    private List<Map<String, String>> readWeavingRows(MultipartFile file) {
        List<Map<String, String>> rows = new ArrayList<>();
        // 大文件安全：改用磁盘临时文件方式打开，绕过POI流式打开的1亿字节硬上限
        try (Workbook workbook = ExcelUtils.openWorkbookSafely(file)) {
            Sheet sheet = workbook.getSheetAt(0);

            int headerIdx = ExcelUtils.locateHeaderRow(sheet, new String[]{"零件号", "机台号", "班次"}, 6);
            if (headerIdx < 0) headerIdx = 0;
            Row headerRow = sheet.getRow(headerIdx);
            if (headerRow == null) return rows;

            int colPn = -1, colY = -1, colM = -1, colD = -1, colMach = -1, colTape = -1, colModel = -1;
            int colWarp = -1, colWeft = -1, colShift = -1, colWorker = -1, colOutput = -1;
            int colStdCap = -1, colStdHour = -1, colStdHourCap = -1, colPerf = -1, colRemark = -1;
            int colWarpWeight = -1, colWeftWeight2000 = -1, colWeftWeight3000 = -1;
            int colWarpUsage = -1, colWeftUsage2000 = -1, colWeftUsage3000 = -1;

            for (int j = 0; j < headerRow.getLastCellNum(); j++) {
                // 表头含换行/全角括号，先统一清理再匹配（如"纬线米重\n（2000D）"）
                String h = ExcelUtils.getCellStringValue(headerRow.getCell(j)).trim()
                        .replaceAll("\\s+", "").replace("（", "(").replace("）", ")");
                if (h.isEmpty()) continue;
                // 米重/耗用列优先匹配（含"经线"/"纬线"字样，须先于经线/纬线列判断）
                if (h.contains("米重") && h.contains("经线")) colWarpWeight = j;
                else if (h.contains("米重") && h.contains("2000D")) colWeftWeight2000 = j;
                else if (h.contains("米重") && h.contains("3000D")) colWeftWeight3000 = j;
                else if (h.contains("耗用") && h.contains("经线")) colWarpUsage = j;
                else if (h.contains("耗用") && h.contains("2000D")) colWeftUsage2000 = j;
                else if (h.contains("耗用") && h.contains("3000D")) colWeftUsage3000 = j;
                else if (h.equals("零件号") || h.equals("带坯零件号") || h.contains("产品编号")) colPn = j;
                else if (h.equals("年")) colY = j;
                else if (h.equals("月")) colM = j;
                else if (h.equals("日")) colD = j;
                else if (h.contains("机台")) colMach = j;
                else if (h.contains("带坯") && (h.contains("编号") || h.contains("卷号")) || h.equals("编号") || h.equals("卷号")) colTape = j;
                else if (h.contains("型号") || h.contains("规格")) colModel = j;
                else if (h.contains("经线")) colWarp = j;
                else if (h.contains("纬线")) colWeft = j;
                else if (h.contains("班次")) colShift = j;
                else if (h.contains("姓名") || h.contains("操作工")) colWorker = j;
                else if (h.contains("当班产量") || (h.contains("产量") && !h.contains("标准") && !h.contains("小时"))) colOutput = j;
                else if (h.contains("标准产能")) colStdCap = j;
                else if (h.contains("标准工时") || (h.contains("标准小时") && !h.contains("产能"))) colStdHour = j;
                else if (h.contains("小时产能")) colStdHourCap = j;
                else if (h.contains("绩效")) colPerf = j;
                else if (h.contains("备注")) colRemark = j;
            }

            int[] logicalCols = {colPn, colY, colM, colD, colMach, colTape, colModel, colWarp, colWeft,
                    colShift, colWorker, colOutput, colStdCap, colStdHour, colStdHourCap, colPerf, colRemark,
                    colWarpWeight, colWeftWeight2000, colWeftWeight3000, colWarpUsage, colWeftUsage2000, colWeftUsage3000};

            for (int i = headerIdx + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                Map<String, String> rowMap = new HashMap<>();
                boolean anyValue = false;
                for (int p = 0; p < logicalCols.length; p++) {
                    String value = logicalCols[p] >= 0 ? ExcelUtils.getCellStringValue(row.getCell(logicalCols[p])) : "";
                    rowMap.put("col" + p, value);
                    if (!value.isEmpty()) anyValue = true;
                }
                if (anyValue) rows.add(rowMap);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("织造Excel解析失败: " + e.getMessage(), e);
        }
        return rows;
    }

    // =========================================================================
    // 清洗记录 → 实体映射
    // =========================================================================

    /**
     * 将清洗后的记录映射为WeavingDailyLog实体
     * 兼容Python清洗器键名(beltNo/spec/shift等)与实体字段键名(tapeCode/modelSpec等)
     * 必填字段缺失时返回null（计为跳过）
     */
    private WeavingDailyLog mapToEntity(Map<String, Object> record) {
        Integer year = toInteger(record.get("entryYear"));
        Integer month = toInteger(record.get("entryMonth"));
        Integer day = toInteger(record.get("entryDay"));
        LocalDate entryDate = parseDate(record.get("entryDate"), year, month, day);
        Integer machineNo = toInteger(record.get("machineNo"));
        String modelSpec = str(record, "modelSpec", "spec");
        String shiftType = str(record, "shiftType", "shift");

        // 实体非空约束校验（entryYear/entryMonth/entryDay/entryDate/machineNo/modelSpec/shiftType）
        if (year == null || month == null || day == null || entryDate == null
                || machineNo == null || modelSpec == null || shiftType == null) {
            log.warn("织造记录关键字段缺失，跳过: {}", record);
            return null;
        }

        WeavingDailyLog entity = new WeavingDailyLog();
        entity.setPartNumber(str(record, "partNumber"));
        entity.setEntryYear(year);
        entity.setEntryMonth(month);
        entity.setEntryDay(day);
        entity.setEntryDate(entryDate);
        entity.setMachineNo(machineNo);
        String tapeCode = str(record, "tapeCode", "beltNo");
        entity.setTapeCode(tapeCode == null ? "DEFAULT" : tapeCode);
        entity.setModelSpec(modelSpec);
        entity.setWarpThread(str(record, "warpThread", "warp"));
        entity.setWeftThread(str(record, "weftThread", "weft"));
        entity.setShiftType(shiftType);
        entity.setWorkerName(str(record, "workerName", "operator"));
        entity.setShiftOutput(toBigDecimal(firstNonNull(record.get("shiftOutput"), record.get("outputQty"))));
        entity.setStandardCapacity(toBigDecimal(firstNonNull(record.get("standardCapacity"), record.get("stdCapacity"))));
        entity.setStandardHours(toBigDecimal(firstNonNull(record.get("standardHours"), record.get("stdHour"))));
        entity.setStandardHourCapacity(toBigDecimal(firstNonNull(record.get("standardHourCapacity"), record.get("stdHourCapacity"))));
        entity.setPerformanceHours(toBigDecimal(firstNonNull(record.get("performanceHours"), record.get("perfHour"))));
        entity.setRemark(str(record, "remark"));
        // 新增6列：米重/耗用（透传，无校验，允许为空）
        entity.setWarpWeightPerMeter(toBigDecimal(record.get("warpWeightPerMeter")));
        entity.setWeftWeightPerMeter2000D(toBigDecimal(record.get("weftWeightPerMeter2000D")));
        entity.setWeftWeightPerMeter3000D(toBigDecimal(record.get("weftWeightPerMeter3000D")));
        entity.setWarpUsageKgPerMeter(toBigDecimal(record.get("warpUsageKgPerMeter")));
        entity.setWeftUsageKgPerMeter2000D(toBigDecimal(record.get("weftUsageKgPerMeter2000D")));
        entity.setWeftUsageKgPerMeter3000D(toBigDecimal(record.get("weftUsageKgPerMeter3000D")));
        String grade = str(record, "grade", "dataQualityFlag");
        entity.setDataQualityFlag(grade == null ? "A" : grade);
        entity.setDataSource("EXCEL_IMPORT");
        return entity;
    }

    /**
     * B级记录是否满足升级为A级的条件
     */
    private boolean isQualifiedForGradeA(WeavingDailyLog record) {
        if (record.getPartNumber() == null || record.getPartNumber().isEmpty()) return false;
        if (record.getStandardCapacity() == null || record.getStandardHours() == null
                || record.getStandardHours().signum() <= 0) return false;
        // 标准小时产能 ≈ 标准产能/标准小时（5%容差）
        if (record.getStandardHourCapacity() != null) {
            BigDecimal expected = record.getStandardCapacity().divide(record.getStandardHours(), 4, RoundingMode.HALF_UP);
            if (!closeEnough(record.getStandardHourCapacity(), expected)) return false;
        }
        // 绩效工时 ≈ 当班产量/标准小时产能（5%容差）
        if (record.getShiftOutput() != null && record.getStandardHourCapacity() != null
                && record.getStandardHourCapacity().signum() > 0 && record.getPerformanceHours() != null) {
            BigDecimal expectedPerf = record.getShiftOutput().divide(record.getStandardHourCapacity(), 4, RoundingMode.HALF_UP);
            if (!closeEnough(record.getPerformanceHours(), expectedPerf)) return false;
        }
        return true;
    }

    private boolean closeEnough(BigDecimal actual, BigDecimal expected) {
        if (expected.signum() == 0) return actual.signum() == 0;
        BigDecimal diff = actual.subtract(expected).abs();
        return diff.compareTo(expected.abs().multiply(new BigDecimal("0.05"))) <= 0;
    }

    // =========================================================================
    // 类型转换辅助
    // =========================================================================

    private String str(Map<String, Object> record, String... keys) {
        for (String key : keys) {
            Object v = record.get(key);
            if (v != null) {
                String s = v.toString().trim();
                if (!s.isEmpty()) return s;
            }
        }
        return null;
    }

    private Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
    }

    private Integer toInteger(Object v) {
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return (int) Double.parseDouble(v.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal toBigDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal) return (BigDecimal) v;
        if (v instanceof Number) return new BigDecimal(v.toString());
        return ExcelUtils.parseBigDecimalSafely(v.toString());
    }

    private LocalDate parseDate(Object v, Integer year, Integer month, Integer day) {
        if (v != null) {
            try {
                String s = v.toString().trim();
                if (s.contains(" ")) s = s.split(" ")[0];
                if (s.contains("/")) s = s.replace("/", "-");
                if (!s.isEmpty()) return LocalDate.parse(s);
            } catch (Exception ignored) {
            }
        }
        if (year != null && month != null && day != null) {
            try {
                return LocalDate.of(year, month, day);
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
