package com.company.scheduling.service.scheduling;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 带坯供给模拟器（纯计算工具类，需求6：多织造机台对单/少共挤产线的换带坯时点标记）
 *
 * 场景：多台织造机台供给单条/少数共挤产线时，共挤生产中途需从一台机台的带坯切换到
 * 另一台（或从库存整根切换到机台织造带坯）。本类按"累计供给 + 提前库存(储备) < 累计消耗"
 * 自共挤开工时刻起逐段递推切换时点，产出 tapeChangeEvents 供共挤甘特条竖线标记展示。
 *
 * 模型说明：
 * - 耗带速率 = cCap / 24 米/小时（共挤日产能折算小时粒度推进）；
 * - 供带速率 = wCap / 24 米/小时（仅用于持续供给能力校验，不参与切换时点递推）；
 * - 共挤开工时刻 coexStart 由排产/询单引擎传入，已含需求5储备米数约束（本类只读取不重算）；
 * - 开工初始可用缓冲 = reserveMeters（需求5储备米数），其后按供给段顺序逐段消耗；
 * - 递推公式：t_k = coexStart + (reserveMeters + Σ 前 k 段可供米数) / (cCap / 24)；
 * - 仅当该产线绑定 ≥2 个带坯来源（≥2 台织造机台，或存在库存整根切换）时输出事件；
 *   1对1 且无库存切换时不输出（避免误显示）；
 * - 不改表结构、不参与 ScheduleCommitter 落库，事件仅挂载预览/询单 draftItem（弱类型 Map）。
 */
public final class TapeSupplySimulator {

    /** 带坯供给段来源类型 */
    public enum SourceType { MACHINE, STOCK }

    /** 单个带坯供给段：一台织造机台的 splitShortfall 配额，或一根库存整根余量 */
    public static class SupplySegment {
        /** 来源标识：织造机台 machineId 或库存 tapeCode */
        public final String sourceId;
        public final SourceType sourceType;
        /** 该段可供米数 */
        public final BigDecimal meters;

        private SupplySegment(String sourceId, SourceType sourceType, BigDecimal meters) {
            this.sourceId = sourceId;
            this.sourceType = sourceType;
            this.meters = meters != null ? meters : BigDecimal.ZERO;
        }

        /** 织造机台供给段（可供米数 = splitShortfall 配额） */
        public static SupplySegment machine(String machineId, BigDecimal meters) {
            return new SupplySegment(machineId, SourceType.MACHINE, meters);
        }

        /** 库存整根供给段（可供米数 = 整根余量，共挤开工即可用） */
        public static SupplySegment stock(String tapeCode, BigDecimal meters) {
            return new SupplySegment(tapeCode, SourceType.STOCK, meters);
        }
    }

    private TapeSupplySimulator() {
    }

    /**
     * 模拟共挤生产过程中的带坯切换事件。
     *
     * @param lineId         共挤产线ID（挂载到事件上，可为 null 表示待指派）
     * @param coexStart      共挤开始时间（引擎已按需求5储备约束计算，本类直接起算）
     * @param cCap           共挤产能（米/天）
     * @param wCapPerMachine 单台织造产能（米/天），用于"织造合计 < 共挤"持续供给校验
     * @param reserveMeters  需求5储备米数（开工初始可用缓冲，只读取其结果）
     * @param supplySegments 该产线按消耗顺序的带坯供给段（建议先库存整根后织造机台）
     * @param warnings       冲突告警收集容器（可 null；复用 ResourceAllocator.ResourceTimeline
     *                       .addConflictWarning 同一 List 容器机制）
     * @return tapeChangeEvents：[{time, fromMachineId, toMachineId, lineId, reason}]
     */
    public static List<Map<String, Object>> simulateTapeChanges(
            String lineId, LocalDateTime coexStart,
            BigDecimal cCap, BigDecimal wCapPerMachine, BigDecimal reserveMeters,
            List<SupplySegment> supplySegments, List<String> warnings) {

        if (coexStart == null || cCap == null || cCap.compareTo(BigDecimal.ZERO) <= 0) {
            return Collections.emptyList();
        }

        // 过滤无效供给段（空来源标识 / 米数非正）
        List<SupplySegment> segments = new ArrayList<>();
        int machineSegments = 0;
        if (supplySegments != null) {
            for (SupplySegment seg : supplySegments) {
                if (seg == null || seg.sourceId == null || seg.sourceId.isBlank()) continue;
                if (seg.meters.compareTo(BigDecimal.ZERO) <= 0) continue;
                segments.add(seg);
                if (seg.sourceType == SourceType.MACHINE) machineSegments++;
            }
        }

        // 校验：织造产率合计(wCap×机台数) < 共挤产率(cCap) → 不满足持续供给，写冲突告警；
        // 产线未指派（lineId=null，超候选待指派行）时跳过断供检查，避免产生"产线 N/A"告警噪音
        if (warnings != null && lineId != null && machineSegments > 0 && wCapPerMachine != null) {
            BigDecimal weavingTotal = wCapPerMachine.multiply(new BigDecimal(machineSegments));
            if (weavingTotal.compareTo(cCap) < 0) {
                warnings.add("产线 " + lineId
                        + "：织造总产率 " + weavingTotal.stripTrailingZeros().toPlainString()
                        + " 米/天（" + machineSegments + " 台）低于共挤产率 "
                        + cCap.stripTrailingZeros().toPlainString()
                        + " 米/天，共挤中途可能断供带坯，请增配织造机台或调低共挤产能");
            }
        }

        // 输出门槛：带坯来源 ≥2 个才产生切换事件；1对1 且无库存切换（单来源）时不输出
        if (segments.size() < 2) {
            return Collections.emptyList();
        }

        // 耗带速率 cCap/24 米/小时；初始缓冲 = 需求5储备米数
        BigDecimal consumptionRatePerHour = cCap.divide(new BigDecimal("24"), 6, RoundingMode.HALF_UP);
        BigDecimal cumulative = reserveMeters != null ? reserveMeters.max(BigDecimal.ZERO) : BigDecimal.ZERO;

        List<Map<String, Object>> events = new ArrayList<>();
        // 逐段递推：消耗量越过相邻供给段边界（前一段耗尽）时发生一次换带坯
        for (int k = 0; k < segments.size() - 1; k++) {
            SupplySegment from = segments.get(k);
            SupplySegment to = segments.get(k + 1);
            cumulative = cumulative.add(from.meters);
            if (from.sourceId.equals(to.sourceId)) continue; // 同源不产生切换，但米数照常累计

            // t_k = coexStart + 累计可供米数 / (cCap/24)，小时粒度推进
            BigDecimal hours = cumulative.divide(consumptionRatePerHour, 4, RoundingMode.HALF_UP);
            LocalDateTime switchTime = coexStart.plusMinutes(hours.multiply(new BigDecimal("60")).longValue());

            Map<String, Object> event = new LinkedHashMap<>();
            event.put("time", switchTime.toString());
            event.put("fromMachineId", from.sourceId);
            event.put("toMachineId", to.sourceId);
            event.put("lineId", lineId);
            event.put("reason", describe(from) + " 供给耗尽，切换至 " + describe(to));
            events.add(event);
        }
        return events;
    }

    private static String describe(SupplySegment seg) {
        return seg.sourceType == SourceType.STOCK
                ? "库存整根 " + seg.sourceId
                : "机台 " + seg.sourceId;
    }
}
