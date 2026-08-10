package com.company.scheduling.util;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Excel 单元格读取与安全类型转换静态工具类
 */
public final class ExcelUtils {

    private ExcelUtils() {}

    /**
     * 大文件安全的Workbook打开方式
     * POI 5.2.x 中通过InputStream打开xlsx时，会把每个zip条目整体读入内存，
     * 并受 IOUtils.safelyAllocateCheck 的1亿字节硬上限约束（该检查忽略
     * setByteArrayMaxOverride，属官方已知问题）。大文件会直接报
     * "Tried to allocate an array of length ..., maximum length ... 100,000,000"。
     * 改用磁盘临时文件+File方式打开可完全绕过该限制，且降低内存峰值。
     *
     * @param file 上传的Excel文件
     * @return 打开的Workbook，调用方负责close
     */
    public static Workbook openWorkbookSafely(MultipartFile file) throws Exception {
        File temp = File.createTempFile("import_", "_" + sanitize(file.getOriginalFilename()));
        try {
            file.transferTo(temp.getAbsoluteFile());
            return WorkbookFactory.create(temp);
        } finally {
            // 内容已加载进内存，可删除临时文件（Windows上删除失败也不影响使用）
            if (!temp.delete()) temp.deleteOnExit();
        }
    }

    private static String sanitize(String name) {
        if (name == null || name.isEmpty()) return "upload.xlsx";
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * 安全地将 Excel Cell 转换为字符串
     */
    public static String getCellValueAsString(Cell cell) {
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

    /**
     * 安全地将字符串解析为 BigDecimal，失败返回 null
     */
    public static BigDecimal parseBigDecimalSafely(String str) {
        if (str == null || str.trim().isEmpty()) return null;
        try { return new BigDecimal(str.trim()); } catch (Exception e) { return null; }
    }

    /**
     * 安全地将字符串解析为 Integer，失败返回 null
     */
    public static Integer parseIntegerSafely(String str) {
        if (str == null || str.trim().isEmpty()) return null;
        try { return (int) Double.parseDouble(str.trim()); } catch (Exception e) { return null; }
    }

    /**
     * 在前N行中扫描查找表头行
     * 可处理库存Excel中表头偏移（如第3行）的情况
     *
     * @param sheet       Excel Sheet
     * @param keywords    表头关键字数组（如 ["零件号", "机台号", "班次"]）
     * @param maxScanRows 最大扫描行数（默认5）
     * @return 表头所在行索引，未找到返回-1
     */
    public static int locateHeaderRow(Sheet sheet, String[] keywords, int maxScanRows) {
        if (sheet == null || keywords == null || keywords.length == 0) return -1;
        int scanLimit = maxScanRows > 0 ? maxScanRows : 5;
        int lastRow = Math.min(sheet.getLastRowNum(), scanLimit - 1);
        for (int r = 0; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            int matched = 0;
            for (String keyword : keywords) {
                if (keyword == null || keyword.isEmpty()) continue;
                boolean found = false;
                for (int c = row.getFirstCellNum(); c >= 0 && c < row.getLastCellNum(); c++) {
                    String value = getCellStringValue(row.getCell(c));
                    if (value.contains(keyword)) {
                        found = true;
                        break;
                    }
                }
                if (found) matched++;
            }
            // 所有关键字均命中则认定为表头行
            if (matched == keywords.length) return r;
        }
        return -1;
    }

    /**
     * 从文件名提取年份
     * 正则: (20)\d{2} 提取4位年份，如 "01水电气统计202606.xlsx" → 2026
     * 也支持2位年份如 "25" → 2025
     *
     * @param fileName 文件名
     * @return 年份，解析失败返回null
     */
    public static Integer extractYearFromFileName(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) return null;
        // 优先匹配4位年份（20xx/19xx）
        Matcher fourDigit = Pattern.compile("(20|19)\\d{2}").matcher(fileName);
        if (fourDigit.find()) {
            return Integer.parseInt(fourDigit.group());
        }
        // 其次匹配独立的2位年份
        Matcher twoDigit = Pattern.compile("(?:^|[^0-9])(\\d{2})(?:[^0-9]|$)").matcher(fileName);
        if (twoDigit.find()) {
            return 2000 + Integer.parseInt(twoDigit.group(1));
        }
        return null;
    }

    /**
     * Excel日期序列号转LocalDate
     * 如 46023 → 2026-01-01
     *
     * @param serialNumber Excel日期序列号
     * @return LocalDate，非法序列号返回null
     */
    public static LocalDate excelSerialToLocalDate(double serialNumber) {
        try {
            // Excel以1899-12-30为基准日（含1900闰年bug补偿）
            return LocalDate.of(1899, 12, 30).plusDays((long) serialNumber);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 安全获取单元格值（统一为String）
     * 处理NUMERIC/STRING/BOOLEAN/FORMULA/BLANK等所有类型
     */
    public static String getCellStringValue(Cell cell) {
        if (cell == null) return "";
        try {
            switch (cell.getCellType()) {
                case STRING:
                    return cell.getStringCellValue().trim();
                case NUMERIC:
                    if (DateUtil.isCellDateFormatted(cell)) {
                        return cell.getLocalDateTimeCellValue() == null ? ""
                                : cell.getLocalDateTimeCellValue().toLocalDate().toString();
                    }
                    double val = cell.getNumericCellValue();
                    if (val == (long) val) return String.valueOf((long) val);
                    return String.valueOf(val);
                case BOOLEAN:
                    return String.valueOf(cell.getBooleanCellValue());
                case FORMULA:
                    return getFormulaCellValue(cell);
                case BLANK:
                default:
                    return "";
            }
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 读取公式单元格的缓存结果值
     */
    private static String getFormulaCellValue(Cell cell) {
        try {
            CellType cachedType = cell.getCachedFormulaResultType();
            switch (cachedType) {
                case STRING:
                    return cell.getStringCellValue().trim();
                case NUMERIC:
                    if (DateUtil.isCellDateFormatted(cell)) {
                        return cell.getLocalDateTimeCellValue() == null ? ""
                                : cell.getLocalDateTimeCellValue().toLocalDate().toString();
                    }
                    double val = cell.getNumericCellValue();
                    if (val == (long) val) return String.valueOf((long) val);
                    return String.valueOf(val);
                case BOOLEAN:
                    return String.valueOf(cell.getBooleanCellValue());
                default:
                    return "";
            }
        } catch (Exception e) {
            try { return cell.getStringCellValue().trim(); }
            catch (Exception ex) { return ""; }
        }
    }
}
