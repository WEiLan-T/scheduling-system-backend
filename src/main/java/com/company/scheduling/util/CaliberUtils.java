package com.company.scheduling.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 口径解析与匹配工具类（v2：Integer min/max 模型）
 * <p>核心语义变更：规格字符串（如 "16-20"）仅 "-" 后的整数为口径值（20），
 * 与机台/产线的 caliberMin/caliberMax 做 {@code >= min && <= max} 判定。</p>
 */
public final class CaliberUtils {

    private static final Logger log = LoggerFactory.getLogger(CaliberUtils.class);

    private CaliberUtils() {}

    // ==================== 口径值提取 ====================

    /**
     * 从规格字符串中提取口径值：取 "-" 后的最后一个整数段。
     * <ul>
     *   <li>"16-20" → 20</li>
     *   <li>"20" → 20</li>
     *   <li>"16-20.5" → 20（向下取整）</li>
     *   <li>null / 空 / 解析失败 → null</li>
     * </ul>
     */
    public static Integer extractCaliberValue(String spec) {
        if (spec == null || spec.trim().isEmpty()) return null;
        String normalized = spec.replace('－', '-')
                .replaceAll("\\s+", "")
                .replaceAll("[^0-9.\\-]", "");
        if (normalized.isEmpty()) return null;

        Double last = null;
        for (String seg : normalized.split("-")) {
            if (seg.isEmpty()) continue;
            try {
                last = Double.parseDouble(seg);
            } catch (NumberFormatException ignored) {
                // 跳过无法解析的段
            }
        }
        return last != null ? (int) Math.round(last) : null;
    }

    /**
     * 兼容旧签名：返回 Double 形式的口径值（取 "-" 后最后数值段）。
     */
    public static Double extractCaliber(String spec) {
        Integer v = extractCaliberValue(spec);
        return v != null ? v.doubleValue() : null;
    }

    // ==================== 口径匹配（新 Integer min/max 模型） ====================

    /**
     * 口径匹配：口径值须满足 {@code min <= caliberValue <= max}。
     * <ul>
     *   <li>min 和 max 均为 null → true（不限口径）</li>
     *   <li>caliberValue 为 null → true（fail-open，防脏数据导致全部落选）</li>
     *   <li>仅 min 非 null → caliberValue &gt;= min</li>
     *   <li>仅 max 非 null → caliberValue &lt;= max</li>
     *   <li>均非 null → min &lt;= caliberValue &lt;= max</li>
     * </ul>
     */
    public static boolean isCaliberMatch(Integer caliberValue, Integer min, Integer max) {
        if (min == null && max == null) return true;
        if (caliberValue == null) return true; // fail-open
        if (min != null && caliberValue < min) return false;
        if (max != null && caliberValue > max) return false;
        return true;
    }

    /**
     * 从规格字符串提取口径值后做匹配（便捷委托）。
     */
    public static boolean isCaliberMatch(String spec, Integer min, Integer max) {
        return isCaliberMatch(extractCaliberValue(spec), min, max);
    }

    // ==================== 紧密度评分（新 Integer min/max 模型） ====================

    /**
     * 口径紧密度评分：值越大越紧密（机台/产线区间对口径值的冗余裕量越小越优）。
     * <p>surplus = (max - caliberValue) + (caliberValue - min) = max - min，score = 1000 - surplus。</p>
     * <p>边界：caliberValue/min/max 任一为 null → 返回 0（中性分）。</p>
     */
    public static int caliberFitScore(Integer caliberValue, Integer min, Integer max) {
        if (caliberValue == null || min == null || max == null) return 0;
        int surplus = (max - caliberValue) + (caliberValue - min);
        return 1000 - surplus;
    }

    /**
     * 从规格字符串提取口径值后评分（便捷委托）。
     */
    public static int caliberFitScore(String spec, Integer min, Integer max) {
        return caliberFitScore(extractCaliberValue(spec), min, max);
    }

    // ==================== 辅助 ====================

    /**
     * 格式化口径范围为展示字符串（如 "0-250"），null 时返回空串。
     */
    public static String formatCaliberRange(Integer min, Integer max) {
        if (min == null && max == null) return "";
        return (min != null ? min : "?") + "-" + (max != null ? max : "?");
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
