package com.company.scheduling.service;

import com.company.scheduling.dto.DataQualityReport;
import com.company.scheduling.util.ExcelUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Java内置简化清洗逻辑（Python清洗的降级方案）
 * 规则简化版：仅做基本类型转换和必填校验
 */
@Service
public class JavaDataCleaner {

    private static final Logger log = LoggerFactory.getLogger(JavaDataCleaner.class);

    /**
     * Java内置简化清洗逻辑（Python降级方案）
     *
     * @param rows       Excel原始行数据
     * @param dataType   数据类型 weaving/coex/inventory
     * @param sourceYear 从文件名提取的年份（共挤数据使用）
     */
    public DataQualityReport clean(List<Map<String, String>> rows, String dataType, Integer sourceYear) {
        log.info("使用Java内置清洗降级方案, dataType={}, rows={}", dataType, rows == null ? 0 : rows.size());
        if (rows == null) {
            rows = new ArrayList<>();
        }
        switch (dataType) {
            case "weaving":
                return cleanWeaving(rows);
            case "coex":
                return cleanCoex(rows, sourceYear);
            case "inventory":
                return cleanInventory(rows);
            default:
                throw new IllegalArgumentException("未知的数据类型: " + dataType);
        }
    }

    // ============================================================
    // 织造：年份+2000、日期合并、必填校验
    // ============================================================
    private DataQualityReport cleanWeaving(List<Map<String, String>> rows) {
        List<Map<String, Object>> cleaned = new ArrayList<>();
        List<String> bDetails = new ArrayList<>();
        List<String> cReasons = new ArrayList<>();

        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> row = rows.get(i);
            int lineNo = i + 2;
            String grade = "A";
            List<String> reasonsB = new ArrayList<>();
            List<String> reasonsC = new ArrayList<>();

            String partNumber = get(row, 0);
            Integer year = normYear(get(row, 1));
            Integer month = ExcelUtils.parseIntegerSafely(get(row, 2));
            Integer day = ExcelUtils.parseIntegerSafely(get(row, 3));
            String machineNo = get(row, 4);
            String spec = get(row, 6);
            String shift = get(row, 9);
            BigDecimal outputQty = ExcelUtils.parseBigDecimalSafely(get(row, 11));

            // 核心字段非空：型号规格、班次
            if (spec.isEmpty()) {
                grade = "C";
                reasonsC.add("型号规格为空");
            }
            if (shift.isEmpty()) {
                grade = "C";
                reasonsC.add("班次为空");
            }
            // 班次仅允许白/夜
            if (!shift.isEmpty() && !"白".equals(shift) && !"夜".equals(shift)) {
                grade = "C";
                reasonsC.add("班次非法");
            }
            // 机台号必须为正整数
            Integer machineNum = ExcelUtils.parseIntegerSafely(machineNo);
            if (!machineNo.isEmpty() && (machineNum == null || machineNum <= 0)) {
                grade = "C";
                reasonsC.add("机台号格式异常");
            }
            // 零件号为空 → B级（待关联）
            if (partNumber.isEmpty()) {
                if ("A".equals(grade)) grade = "B";
                reasonsB.add("零件号为空");
            }
            // 日期合并与合法性
            String entryDate = null;
            if (year != null && month != null && day != null && isValidDate(year, month, day)) {
                entryDate = String.format("%04d-%02d-%02d", year, month, day);
            } else {
                grade = "C";
                reasonsC.add("日期非法");
            }
            // 当班产量 <=0 或 >10000 → C级（与Python规则5一致，拒绝负数）
            if (outputQty == null || outputQty.signum() <= 0
                    || outputQty.compareTo(new BigDecimal("10000")) > 0) {
                grade = "C";
                reasonsC.add("当班产量异常");
            }
            // 标准产能/标准小时为空 → B级
            if (ExcelUtils.parseBigDecimalSafely(get(row, 12)) == null) {
                if ("A".equals(grade)) grade = "B";
                reasonsB.add("标准产能为空");
            }
            if (ExcelUtils.parseBigDecimalSafely(get(row, 13)) == null) {
                if ("A".equals(grade)) grade = "B";
                reasonsB.add("标准小时为空");
            }

            Map<String, Object> record = new HashMap<>();
            record.put("partNumber", partNumber.isEmpty() ? null : partNumber);
            record.put("entryDate", entryDate);
            record.put("entryYear", year);
            record.put("entryMonth", month);
            record.put("entryDay", day);
            record.put("machineNo", machineNum != null ? machineNum : (machineNo.isEmpty() ? null : machineNo));
            record.put("beltNo", emptyToNull(get(row, 5)));
            record.put("spec", emptyToNull(spec));
            record.put("shift", emptyToNull(shift));
            record.put("operator", emptyToNull(get(row, 10)));
            record.put("outputQty", outputQty);
            record.put("warp", emptyToNull(get(row, 7)));
            record.put("weft", emptyToNull(get(row, 8)));
            record.put("stdCapacity", ExcelUtils.parseBigDecimalSafely(get(row, 12)));
            record.put("stdHour", ExcelUtils.parseBigDecimalSafely(get(row, 13)));
            record.put("stdHourCapacity", ExcelUtils.parseBigDecimalSafely(get(row, 14)));
            record.put("perfHour", ExcelUtils.parseBigDecimalSafely(get(row, 15)));
            record.put("remark", emptyToNull(get(row, 16)));
            // 新增6列(col17..col22)：米重/耗用，仅透传不加校验，与data_cleaner.py行为对齐
            record.put("warpWeightPerMeter", ExcelUtils.parseBigDecimalSafely(get(row, 17)));
            record.put("weftWeightPerMeter2000D", ExcelUtils.parseBigDecimalSafely(get(row, 18)));
            record.put("weftWeightPerMeter3000D", ExcelUtils.parseBigDecimalSafely(get(row, 19)));
            record.put("warpUsageKgPerMeter", ExcelUtils.parseBigDecimalSafely(get(row, 20)));
            record.put("weftUsageKgPerMeter2000D", ExcelUtils.parseBigDecimalSafely(get(row, 21)));
            record.put("weftUsageKgPerMeter3000D", ExcelUtils.parseBigDecimalSafely(get(row, 22)));
            record.put("grade", grade);

            if ("C".equals(grade)) {
                cReasons.add("第" + lineNo + "行: " + String.join("；", reasonsC));
            } else {
                cleaned.add(record);
                if ("B".equals(grade)) {
                    bDetails.add("第" + lineNo + "行: " + String.join("；", reasonsB));
                }
            }
        }
        return buildReport(rows.size(), cleaned, bDetails, cReasons);
    }

    // ============================================================
    // 共挤：日期序列号转换、机台号转字符串
    // ============================================================
    private DataQualityReport cleanCoex(List<Map<String, String>> rows, Integer sourceYear) {
        List<Map<String, Object>> cleaned = new ArrayList<>();
        List<String> bDetails = new ArrayList<>();
        List<String> cReasons = new ArrayList<>();

        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> row = rows.get(i);
            int lineNo = i + 2;
            String grade = "A";
            List<String> reasonsC = new ArrayList<>();

            String timeRaw = get(row, 0);
            String machineNo = get(row, 1);
            String productType = get(row, 2);
            String material = get(row, 5);
            BigDecimal finishedQty = ExcelUtils.parseBigDecimalSafely(get(row, 6));
            BigDecimal weight = ExcelUtils.parseBigDecimalSafely(get(row, 7));
            BigDecimal capacity = ExcelUtils.parseBigDecimalSafely(get(row, 8));

            // 时间转换：Excel日期序列号 → ISO日期
            String entryDate = null;
            Double serial = parseDoubleSafely(timeRaw);
            if (serial != null) {
                LocalDate date = ExcelUtils.excelSerialToLocalDate(serial);
                if (date != null) {
                    entryDate = date.toString();
                }
            }
            if (entryDate == null) {
                grade = "C";
                reasonsC.add("时间格式异常");
            }
            // 产能 <= 0 → C级
            if (capacity == null || capacity.signum() <= 0) {
                grade = "C";
                reasonsC.add("产能异常");
            }
            // 主材标准化
            String materialStd = material.isEmpty() ? null : normalizeMaterial(material);

            Map<String, Object> record = new HashMap<>();
            record.put("entryDate", entryDate);
            record.put("entryYear", sourceYear);
            record.put("machineNo", machineNo.isEmpty() ? null : machineNo);
            record.put("productType", emptyToNull(productType));
            record.put("productModel", emptyToNull(get(row, 3)));
            record.put("color", emptyToNull(get(row, 4)));
            record.put("material", materialStd);
            record.put("finishedQty", finishedQty);
            record.put("weight", weight);
            record.put("capacity", capacity);
            record.put("glueLeak", emptyToNull(get(row, 9)));
            record.put("grade", grade);

            if ("C".equals(grade)) {
                cReasons.add("第" + lineNo + "行: " + String.join("；", reasonsC));
            } else {
                cleaned.add(record);
            }
        }
        return buildReport(rows.size(), cleaned, bDetails, cReasons);
    }

    // ============================================================
    // 库存：必填校验、斜杠拆分、数量空值→0
    // ============================================================
    private DataQualityReport cleanInventory(List<Map<String, String>> rows) {
        List<Map<String, Object>> cleaned = new ArrayList<>();
        List<String> bDetails = new ArrayList<>();
        List<String> cReasons = new ArrayList<>();

        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> row = rows.get(i);
            int lineNo = i + 2;

            String partNumber = get(row, 0);
            String beltNo = get(row, 1);
            BigDecimal qty = ExcelUtils.parseBigDecimalSafely(get(row, 2));
            if (qty == null) qty = BigDecimal.ZERO;
            String remark = get(row, 3);
            String snapshotDate = get(row, 4); // 动态月份列透传的快照日期
            String machineNo = get(row, 5);    // 机台（非空=在产未落库）

            // 零件号必填
            if (partNumber.isEmpty()) {
                cReasons.add("第" + lineNo + "行: 零件号为空");
                continue;
            }

            // 带坯编号斜杠拆分
            String[] belts = beltNo.isEmpty() ? new String[]{null} : beltNo.split("/");
            for (String b : belts) {
                String single = b == null ? null : b.trim();
                if (single != null && single.isEmpty()) continue;
                Map<String, Object> record = new HashMap<>();
                record.put("partNumber", partNumber);
                record.put("beltNo", single);
                record.put("quantity", qty);
                record.put("remark", classifyRemark(remark));
                record.put("grade", "A");
                record.put("snapshotDate", snapshotDate.isEmpty() ? null : snapshotDate);
                record.put("machineNo", machineNo.isEmpty() ? null : machineNo);
                cleaned.add(record);
            }
        }
        return buildReport(rows.size(), cleaned, bDetails, cReasons);
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    private DataQualityReport buildReport(int totalRows, List<Map<String, Object>> cleaned,
                                          List<String> bDetails, List<String> cReasons) {
        DataQualityReport report = new DataQualityReport();
        report.setTotalRows(totalRows);
        int aCount = 0;
        int bCount = 0;
        for (Map<String, Object> r : cleaned) {
            if ("A".equals(r.get("grade"))) aCount++;
            else if ("B".equals(r.get("grade"))) bCount++;
        }
        report.setGradeACount(aCount);
        report.setGradeBCount(bCount);
        report.setGradeCCount(cReasons.size());
        report.setGradeBDetails(bDetails);
        report.setGradeCReasons(cReasons);
        report.setCleanedData(cleaned);
        return report;
    }

    private String get(Map<String, String> row, int colIdx) {
        String v = row.get("col" + colIdx);
        return v == null ? "" : v.trim();
    }

    private String emptyToNull(String v) {
        return v == null || v.isEmpty() ? null : v;
    }

    private Integer normYear(String raw) {
        Integer n = ExcelUtils.parseIntegerSafely(raw);
        if (n == null) return null;
        if (n >= 0 && n <= 99) return n + 2000;
        return n;
    }

    private boolean isValidDate(int year, int month, int day) {
        if (month < 1 || month > 12 || day < 1 || day > 31) return false;
        int[] monthDays = {31, isLeap(year) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
        return day <= monthDays[month - 1];
    }

    private boolean isLeap(int year) {
        return (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
    }

    private Double parseDoubleSafely(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String normalizeMaterial(String material) {
        if ("81085".equals(material)) return "万华81085";
        if ("81080".equals(material)) return "万华81080";
        return material;
    }

    private String classifyRemark(String remark) {
        if (remark == null || remark.isEmpty()) return "库存";
        if (remark.contains("订单")) return "订单";
        if (remark.contains("库存")) return "库存";
        if (remark.contains("滞留")) return "滞留";
        return remark;
    }
}
