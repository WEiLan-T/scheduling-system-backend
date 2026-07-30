package com.company.scheduling.util;

/**
 * 口径解析与匹配静态工具类
 */
public final class CaliberUtils {

    private CaliberUtils() {}

    /**
     * 从规格字符串中提取口径数值（取 "-" 后的数字部分）
     */
    public static Double extractCaliber(String spec) {
        if (spec == null || spec.trim().isEmpty()) return null;
        String[] parts = spec.split("-");
        String lastPart = parts[parts.length - 1].replaceAll("[^0-9.]", "");
        try { return Double.parseDouble(lastPart); } catch (Exception e) { return null; }
    }

    /**
     * 判断口径是否在限制范围内
     */
    public static boolean isCaliberMatch(Double caliber, String limit) {
        if (caliber == null || limit == null || limit.trim().isEmpty()) return true;
        try {
            String[] parts = limit.split("-");
            double min = Double.parseDouble(parts[0]);
            double max = parts.length > 1 ? Double.parseDouble(parts[1]) : min;
            return caliber >= min && caliber <= max;
        } catch (Exception e) { return true; }
    }

    /**
     * 从车间 ID 中提取车间编号（1/2/3）
     */
    public static Integer extractWorkshopNumber(String workshopId) {
        if (workshopId == null) return null;
        if (workshopId.contains("1") || workshopId.contains("一")) return 1;
        if (workshopId.contains("2") || workshopId.contains("二")) return 2;
        if (workshopId.contains("3") || workshopId.contains("三")) return 3;
        return null;
    }
}
