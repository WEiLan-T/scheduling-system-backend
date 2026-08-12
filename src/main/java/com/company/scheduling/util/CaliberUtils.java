package com.company.scheduling.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 口径解析与匹配静态工具类
 */
public final class CaliberUtils {

    private static final Logger log = LoggerFactory.getLogger(CaliberUtils.class);

    private CaliberUtils() {}

    /**
     * 从规格字符串中提取口径数值（取 "-" 分隔后的最后一个数值段）。
     * <p>委托 {@link #extractCaliberRange(String)} 取其上限值，保持既有调用方行为不变：
     * "16-20" → 20；"16" → 16；解析失败返回 null。</p>
     */
    public static Double extractCaliber(String spec) {
        double[] range = extractCaliberRange(spec);
        return range == null ? null : range[1];
    }

    /**
     * 从规格字符串中提取口径区间 [min, max]。
     * <p>先做归一化（全角"－"转半角"-"、剔除空白及非数字/非小数点/非连字符的脏字符），
     * 再按 "-" 拆分取首、末数值段："16-20" → [16,20]；单值 "16" → [16,16]；
     * 解析失败返回 null。</p>
     */
    public static double[] extractCaliberRange(String spec) {
        if (spec == null || spec.trim().isEmpty()) return null;
        // 归一化：全角连字符转半角，剔除空白与非数字/非小数点/非连字符脏字符
        String normalized = spec.replace('－', '-')
                .replaceAll("\\s+", "")
                .replaceAll("[^0-9.\\-]", "");
        if (normalized.isEmpty()) return null;

        Double first = null;
        Double last = null;
        for (String seg : normalized.split("-")) {
            if (seg.isEmpty()) continue;
            try {
                double v = Double.parseDouble(seg);
                if (first == null) first = v;
                last = v;
            } catch (NumberFormatException ignored) {
                // 单个数值段解析失败，跳过该段
            }
        }
        if (first == null) return null;
        return new double[]{first, last};
    }

    /**
     * 判断规格口径是否落入限制区间（区间判定）。
     * <p>边界语义：</p>
     * <ul>
     *   <li>limit 为 null/空 → true（不限口径）；</li>
     *   <li>limit 解析失败 → WARN 日志并返回 true（fail-open，防存量脏数据导致机台全部落选）；</li>
     *   <li>spec 区间解析失败 → 回退旧 {@link #extractCaliber(String)} 单值判定逻辑；</li>
     *   <li>匹配条件：specMin &gt;= limitMin &amp;&amp; specMax &lt;= limitMax。</li>
     * </ul>
     */
    public static boolean isCaliberMatch(String spec, String limit) {
        // limit 为空 → 不限口径
        if (limit == null || limit.trim().isEmpty()) return true;

        // limit 解析失败 → fail-open
        double[] limitRange = extractCaliberRange(limit);
        if (limitRange == null) {
            log.warn("机台/产线口径限制格式无法解析，按不限口径处理(fail-open): limit={}", limit);
            return true;
        }

        // spec 区间判定
        double[] specRange = extractCaliberRange(spec);
        if (specRange != null) {
            return specRange[0] >= limitRange[0] && specRange[1] <= limitRange[1];
        }

        // spec 区间解析失败 → 回退旧单值判定逻辑（保持旧行为：单值为 null 视为不限）
        Double caliber = extractCaliber(spec);
        if (caliber == null) return true;
        return caliber >= limitRange[0] && caliber <= limitRange[1];
    }

    /**
     * 判断口径单值是否落入限制区间（兼容旧签名的委托入口）。
     */
    public static boolean isCaliberMatch(Double caliber, String limit) {
        return isCaliberMatch(caliber == null ? null : String.valueOf(caliber), limit);
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
