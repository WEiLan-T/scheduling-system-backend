package com.company.scheduling.util;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;

import java.math.BigDecimal;

/**
 * Excel 单元格读取与安全类型转换静态工具类
 */
public final class ExcelUtils {

    private ExcelUtils() {}

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
}
