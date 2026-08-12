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
 * 产能匹配器：负责口径匹配、机台/产线筛选与打分
 */
@Component
public class CapacityMatcher {

    private static final Logger log = LoggerFactory.getLogger(CapacityMatcher.class);

    public Double extractCaliber(String spec) {
        return CaliberUtils.extractCaliber(spec);
    }

    /**
     * 口径区间匹配：规格(spec)区间须落入 limit 区间。
     * 签名随工具层 {@link CaliberUtils#isCaliberMatch(String, String)} 同步。
     */
    public boolean isCaliberMatch(String spec, String limit) {
        return CaliberUtils.isCaliberMatch(spec, limit);
    }

    /** 兼容旧签名：口径单值视为点区间 [caliber, caliber] */
    public boolean isCaliberMatch(Double caliber, String limit) {
        return CaliberUtils.isCaliberMatch(caliber, limit);
    }

    /** 口径单值转规格字符串（null 保持 null，交由工具层按边界语义处理） */
    private static String specOf(Double caliber) {
        return caliber == null ? null : String.valueOf(caliber);
    }

    public Integer extractWorkshopNumber(String workshopId) {
        return CaliberUtils.extractWorkshopNumber(workshopId);
    }

    public List<WeavingMachineStatus> findBestWeavingMachines(Double caliber, List<WeavingMachineStatus> allMachines) {
        return findBestWeavingMachines(caliber, allMachines, null);
    }

    /**
     * 口径过滤候选织造机台（完整规格字符串入口）：直接传入订单/工艺库的原始口径规格串（如 "16-20"），
     * 走 {@link CaliberUtils#isCaliberMatch(String, String)} 区间判定（spec 区间落入 limit 区间），
     * spec 解析失败时由工具层回退单值判定；过滤结果为空时向 conflictWarnings 写入显式告警，禁止静默降级。
     *
     * @param spec             完整规格字符串（可为 null，由工具层按边界语义处理）
     * @param conflictWarnings 告警收集容器（可复用 ResourceAllocator.ResourceTimeline#getConflictWarnings()），可为 null
     */
    public List<WeavingMachineStatus> findBestWeavingMachinesBySpec(String spec, List<WeavingMachineStatus> allMachines, List<String> conflictWarnings) {
        List<WeavingMachineStatus> candidates = allMachines.stream()
                .filter(m -> isCaliberMatch(spec, m.getCaliberLimit()))
                .collect(Collectors.toList());
        if (candidates.isEmpty()) {
            String warning = "规格口径 [" + spec + "] 无可用织造机台（共 " + allMachines.size() + " 台候选均不满足口径限制 " + describeLimits(allMachines.stream().map(WeavingMachineStatus::getCaliberLimit).distinct().limit(5).collect(Collectors.toList())) + "）";
            log.warn(warning);
            if (conflictWarnings != null) {
                conflictWarnings.add(warning);
            }
        }
        return candidates;
    }

    /**
     * 口径过滤候选织造机台；过滤结果为空时向 conflictWarnings 写入显式告警，禁止静默降级。
     *
     * @param conflictWarnings 告警收集容器（可复用 ResourceAllocator.ResourceTimeline#getConflictWarnings()），可为 null
     */
    public List<WeavingMachineStatus> findBestWeavingMachines(Double caliber, List<WeavingMachineStatus> allMachines, List<String> conflictWarnings) {
        List<WeavingMachineStatus> candidates = allMachines.stream()
                .filter(m -> isCaliberMatch(specOf(caliber), m.getCaliberLimit()))
                .collect(Collectors.toList());
        if (candidates.isEmpty()) {
            String warning = "口径 " + caliber + " 无可用织造机台（共 " + allMachines.size() + " 台候选均不满足口径限制 " + describeLimits(allMachines.stream().map(WeavingMachineStatus::getCaliberLimit).distinct().limit(5).collect(Collectors.toList())) + "）";
            log.warn(warning);
            if (conflictWarnings != null) {
                conflictWarnings.add(warning);
            }
        }
        return candidates;
    }

    public int scoreWeavingMachine(WeavingMachineStatus m, String warpSpec, List<WeavingMachineStatus> all) {
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
        return score;
    }

    public List<CoexLineStatus> findBestCoexLines(Double caliber, List<CoexLineStatus> allLines) {
        return findBestCoexLines(caliber, allLines, null);
    }

    /**
     * 口径过滤候选共挤产线（完整规格字符串入口）：直接传入订单/工艺库的原始口径规格串（如 "16-20"），
     * 走 {@link CaliberUtils#isCaliberMatch(String, String)} 区间判定；过滤结果为空时向 conflictWarnings 写入显式告警，禁止静默降级。
     *
     * @param spec             完整规格字符串（可为 null，由工具层按边界语义处理）
     * @param conflictWarnings 告警收集容器（可复用 ResourceAllocator.ResourceTimeline#getConflictWarnings()），可为 null
     */
    public List<CoexLineStatus> findBestCoexLinesBySpec(String spec, List<CoexLineStatus> allLines, List<String> conflictWarnings) {
        List<CoexLineStatus> candidates = allLines.stream()
                .filter(l -> isCaliberMatch(spec, l.getCaliberLimit()))
                .sorted((a, b) -> Integer.compare("空闲".equals(b.getLineStatus()) ? 1 : 0, "空闲".equals(a.getLineStatus()) ? 1 : 0))
                .collect(Collectors.toList());
        if (candidates.isEmpty()) {
            String warning = "规格口径 [" + spec + "] 无可用共挤产线（共 " + allLines.size() + " 条候选均不满足口径限制 " + describeLimits(allLines.stream().map(CoexLineStatus::getCaliberLimit).distinct().limit(5).collect(Collectors.toList())) + "）";
            log.warn(warning);
            if (conflictWarnings != null) {
                conflictWarnings.add(warning);
            }
        }
        return candidates;
    }

    /**
     * 口径过滤候选共挤产线；过滤结果为空时向 conflictWarnings 写入显式告警，禁止静默降级。
     *
     * @param conflictWarnings 告警收集容器（可复用 ResourceAllocator.ResourceTimeline#getConflictWarnings()），可为 null
     */
    public List<CoexLineStatus> findBestCoexLines(Double caliber, List<CoexLineStatus> allLines, List<String> conflictWarnings) {
        List<CoexLineStatus> candidates = allLines.stream()
                .filter(l -> isCaliberMatch(specOf(caliber), l.getCaliberLimit()))
                .sorted((a, b) -> Integer.compare("空闲".equals(b.getLineStatus()) ? 1 : 0, "空闲".equals(a.getLineStatus()) ? 1 : 0))
                .collect(Collectors.toList());
        if (candidates.isEmpty()) {
            String warning = "口径 " + caliber + " 无可用共挤产线（共 " + allLines.size() + " 条候选均不满足口径限制 " + describeLimits(allLines.stream().map(CoexLineStatus::getCaliberLimit).distinct().limit(5).collect(Collectors.toList())) + "）";
            log.warn(warning);
            if (conflictWarnings != null) {
                conflictWarnings.add(warning);
            }
        }
        return candidates;
    }

    /** 拼接候选口径限制摘要，用于告警信息 */
    private static String describeLimits(List<String> limits) {
        return limits.isEmpty() ? "无档案" : String.join(", ", limits.stream().map(l -> l == null ? "null" : l).collect(Collectors.toList()));
    }

    /**
     * 获取空闲兼容资源（织造机台 + 共挤产线）
     */
    public Map<String, Object> getIdleCompatibleResources(
            Double caliber,
            List<WeavingMachineStatus> allMachines,
            List<CoexLineStatus> allLines
    ) {
        List<Map<String, Object>> idleMachines = allMachines.stream()
                .filter(m -> isCaliberMatch(specOf(caliber), m.getCaliberLimit()))
                .map(m -> {
                    Map<String, Object> info = new HashMap<>();
                    info.put("machineId", m.getMachineId());
                    info.put("caliberLimit", m.getCaliberLimit());
                    info.put("workshopId", m.getWorkshopId());
                    info.put("machineStatus", m.getMachineStatus());
                    return info;
                }).collect(Collectors.toList());

        List<Map<String, Object>> idleLines = allLines.stream()
                .filter(l -> isCaliberMatch(specOf(caliber), l.getCaliberLimit()))
                .map(l -> {
                    Map<String, Object> info = new HashMap<>();
                    info.put("lineId", l.getLineId());
                    info.put("caliberLimit", l.getCaliberLimit());
                    info.put("workshopId", l.getWorkshopId());
                    info.put("lineStatus", l.getLineStatus());
                    return info;
                }).collect(Collectors.toList());

        List<String> conflictWarnings = new ArrayList<>();
        if (idleMachines.isEmpty()) {
            String warning = "口径 " + caliber + " 无口径兼容的织造机台";
            log.warn(warning);
            conflictWarnings.add(warning);
        }
        if (idleLines.isEmpty()) {
            String warning = "口径 " + caliber + " 无口径兼容的共挤产线";
            log.warn(warning);
            conflictWarnings.add(warning);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("idleMachines", idleMachines);
        result.put("idleLines", idleLines);
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
