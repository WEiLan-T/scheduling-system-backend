package com.company.scheduling.service.scheduling;

import com.company.scheduling.domain.CoexLineStatus;
import com.company.scheduling.domain.WeavingMachineStatus;
import com.company.scheduling.util.CaliberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 产能匹配器：负责口径匹配、机台/产线筛选与打分（v2：Integer min/max 模型）
 */
@Component
public class CapacityMatcher {

    private static final Logger log = LoggerFactory.getLogger(CapacityMatcher.class);

    public Integer extractCaliberValue(String spec) {
        return CaliberUtils.extractCaliberValue(spec);
    }

    public Integer extractWorkshopNumber(String workshopId) {
        return CaliberUtils.extractWorkshopNumber(workshopId);
    }

    // ==================== 口径匹配委托 ====================

    /** 规格字符串 vs 织造机台口径匹配 */
    public boolean isCaliberMatchForMachine(String spec, WeavingMachineStatus m) {
        return CaliberUtils.isCaliberMatch(spec, m.getCaliberMin(), m.getCaliberMax());
    }

    /** 规格字符串 vs 共挤产线口径匹配 */
    public boolean isCaliberMatchForLine(String spec, CoexLineStatus l) {
        return CaliberUtils.isCaliberMatch(spec, l.getCaliberMin(), l.getCaliberMax());
    }

    /** 口径紧密度评分委托（规格字符串 vs 织造机台） */
    public int caliberFitScoreForMachine(String spec, WeavingMachineStatus m) {
        return CaliberUtils.caliberFitScore(spec, m.getCaliberMin(), m.getCaliberMax());
    }

    /** 口径紧密度评分委托（规格字符串 vs 共挤产线） */
    public int caliberFitScoreForLine(String spec, CoexLineStatus l) {
        return CaliberUtils.caliberFitScore(spec, l.getCaliberMin(), l.getCaliberMax());
    }

    // ==================== 织造机台筛选 ====================

    /**
     * 口径过滤候选织造机台（完整规格字符串入口）：提取 spec 中口径值，
     * 与各机台的 caliberMin/caliberMax 做 {@code >= min && <= max} 判定，
     * 按紧密度降序排序；过滤结果为空时向 conflictWarnings 写入显式告警。
     *
     * @param spec             完整规格字符串（如 "16-20"，仅 "-" 后的整数为口径值）
     * @param conflictWarnings 告警收集容器，可为 null
     */
    public List<WeavingMachineStatus> findBestWeavingMachinesBySpec(String spec, List<WeavingMachineStatus> allMachines, List<String> conflictWarnings) {
        List<WeavingMachineStatus> candidates = allMachines.stream()
                .filter(m -> isCaliberMatchForMachine(spec, m))
                .sorted((a, b) -> Integer.compare(
                        caliberFitScoreForMachine(spec, b),
                        caliberFitScoreForMachine(spec, a)))
                .collect(Collectors.toList());
        if (candidates.isEmpty()) {
            String warning = "规格口径 [" + spec + "](口径值=" + extractCaliberValue(spec) + ") 无可用织造机台（共 " + allMachines.size() + " 台候选均不满足口径限制 " + describeMachineLimits(allMachines) + "）";
            log.warn(warning);
            if (conflictWarnings != null) {
                conflictWarnings.add(warning);
            }
        }
        return candidates;
    }

    // ==================== 织造机台评分 ====================

    /** 兼容旧三参数签名：spec=null 时不加紧密度分 */
    public int scoreWeavingMachine(WeavingMachineStatus m, String warpSpec, List<WeavingMachineStatus> all) {
        return scoreWeavingMachine(m, warpSpec, null, all);
    }

    /**
     * 织造机台评分（四参数签名）：在原评分基础上追加口径紧密度加分 caliberFitScore/10（约 0~100 分）。
     *
     * @param spec 产品完整规格字符串（可为 null，null 时不加紧密度分）
     */
    public int scoreWeavingMachine(WeavingMachineStatus m, String warpSpec, String spec, List<WeavingMachineStatus> all) {
        int score = 0;
        boolean isIdle = "空闲".equals(m.getMachineStatus());
        if (warpSpec != null && warpSpec.equals(m.getWarpSpec())) score += 100; // 同经线极度优先

        if (isIdle) {
            score += 20;
            if (m.getAdjacentMachine() != null && !m.getAdjacentMachine().trim().isEmpty()) {
                boolean neighborActive = all.stream().anyMatch(n -> m.getAdjacentMachine().equals(n.getMachineId()) && "在产".equals(n.getMachineStatus()));
                if (neighborActive) score += 50; // 一人多机就近安排
            }
        } else {
            score -= 50;
        }

        // 口径紧密度加分（spec 非 null 时生效）
        if (spec != null) {
            score += caliberFitScoreForMachine(spec, m) / 10;
        }
        return score;
    }

    // ==================== 共挤产线筛选 ====================

    /**
     * 口径过滤候选共挤产线（完整规格字符串入口）：按紧密度降序排序，
     * 紧密度相同时空闲状态作为次级排序维度；过滤结果为空时写入告警。
     *
     * @param spec             完整规格字符串
     * @param conflictWarnings 告警收集容器，可为 null
     */
    public List<CoexLineStatus> findBestCoexLinesBySpec(String spec, List<CoexLineStatus> allLines, List<String> conflictWarnings) {
        List<CoexLineStatus> candidates = allLines.stream()
                .filter(l -> isCaliberMatchForLine(spec, l))
                .sorted((a, b) -> {
                    int fitCmp = Integer.compare(
                            caliberFitScoreForLine(spec, b),
                            caliberFitScoreForLine(spec, a));
                    if (fitCmp != 0) return fitCmp;
                    return Integer.compare(
                            "空闲".equals(b.getLineStatus()) ? 1 : 0,
                            "空闲".equals(a.getLineStatus()) ? 1 : 0);
                })
                .collect(Collectors.toList());
        if (candidates.isEmpty()) {
            String warning = "规格口径 [" + spec + "](口径值=" + extractCaliberValue(spec) + ") 无可用共挤产线（共 " + allLines.size() + " 条候选均不满足口径限制 " + describeLineLimits(allLines) + "）";
            log.warn(warning);
            if (conflictWarnings != null) {
                conflictWarnings.add(warning);
            }
        }
        return candidates;
    }

    // ==================== 告警辅助 ====================

    private static String describeMachineLimits(List<WeavingMachineStatus> machines) {
        List<String> limits = machines.stream()
                .map(m -> CaliberUtils.formatCaliberRange(m.getCaliberMin(), m.getCaliberMax()))
                .distinct().limit(5).collect(Collectors.toList());
        return limits.isEmpty() ? "无档案" : String.join(", ", limits);
    }

    private static String describeLineLimits(List<CoexLineStatus> lines) {
        List<String> limits = lines.stream()
                .map(l -> CaliberUtils.formatCaliberRange(l.getCaliberMin(), l.getCaliberMax()))
                .distinct().limit(5).collect(Collectors.toList());
        return limits.isEmpty() ? "无档案" : String.join(", ", limits);
    }

    // ==================== 可用资源查询 ====================

    /**
     * 获取口径兼容的资源（织造机台 + 共挤产线），按规格字符串过滤
     */
    public Map<String, Object> getCompatibleResources(
            String spec,
            List<WeavingMachineStatus> allMachines,
            List<CoexLineStatus> allLines
    ) {
        List<Map<String, Object>> matchedMachines = allMachines.stream()
                .filter(m -> isCaliberMatchForMachine(spec, m))
                .map(m -> {
                    Map<String, Object> info = new HashMap<>();
                    info.put("machineId", m.getMachineId());
                    info.put("caliberMin", m.getCaliberMin());
                    info.put("caliberMax", m.getCaliberMax());
                    info.put("workshopId", m.getWorkshopId());
                    info.put("machineStatus", m.getMachineStatus());
                    return info;
                }).collect(Collectors.toList());

        List<Map<String, Object>> matchedLines = allLines.stream()
                .filter(l -> isCaliberMatchForLine(spec, l))
                .map(l -> {
                    Map<String, Object> info = new HashMap<>();
                    info.put("lineId", l.getLineId());
                    info.put("caliberMin", l.getCaliberMin());
                    info.put("caliberMax", l.getCaliberMax());
                    info.put("workshopId", l.getWorkshopId());
                    info.put("lineStatus", l.getLineStatus());
                    return info;
                }).collect(Collectors.toList());

        List<String> conflictWarnings = new ArrayList<>();
        if (matchedMachines.isEmpty()) {
            String warning = "规格 [" + spec + "] 无口径兼容的织造机台";
            log.warn(warning);
            conflictWarnings.add(warning);
        }
        if (matchedLines.isEmpty()) {
            String warning = "规格 [" + spec + "] 无口径兼容的共挤产线";
            log.warn(warning);
            conflictWarnings.add(warning);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("idleMachines", matchedMachines);
        result.put("idleLines", matchedLines);
        result.put("conflictWarnings", conflictWarnings);
        return result;
    }

    /**
     * 共挤产线评分
     */
    public int scoreCoexLine(CoexLineStatus line) {
        int score = 0;
        if ("空闲".equals(line.getLineStatus())) score += 50;
        else score -= 30;
        return score;
    }
}
