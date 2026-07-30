package com.company.scheduling.service.scheduling;

import com.company.scheduling.domain.CoexLineStatus;
import com.company.scheduling.domain.WeavingMachineStatus;
import com.company.scheduling.util.CaliberUtils;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 产能匹配器：负责口径匹配、机台/产线筛选与打分
 */
@Component
public class CapacityMatcher {

    public Double extractCaliber(String spec) {
        return CaliberUtils.extractCaliber(spec);
    }

    public boolean isCaliberMatch(Double caliber, String limit) {
        return CaliberUtils.isCaliberMatch(caliber, limit);
    }

    public Integer extractWorkshopNumber(String workshopId) {
        return CaliberUtils.extractWorkshopNumber(workshopId);
    }

    public List<WeavingMachineStatus> findBestWeavingMachines(Double caliber, List<WeavingMachineStatus> allMachines) {
        return allMachines.stream()
                .filter(m -> isCaliberMatch(caliber, m.getCaliberLimit()))
                .collect(Collectors.toList());
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
        return allLines.stream()
                .filter(l -> isCaliberMatch(caliber, l.getCaliberLimit()))
                .sorted((a, b) -> Integer.compare("空闲".equals(b.getLineStatus()) ? 1 : 0, "空闲".equals(a.getLineStatus()) ? 1 : 0))
                .collect(Collectors.toList());
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
                .filter(m -> isCaliberMatch(caliber, m.getCaliberLimit()))
                .map(m -> {
                    Map<String, Object> info = new HashMap<>();
                    info.put("machineId", m.getMachineId());
                    info.put("caliberLimit", m.getCaliberLimit());
                    info.put("workshopId", m.getWorkshopId());
                    info.put("machineStatus", m.getMachineStatus());
                    return info;
                }).collect(Collectors.toList());

        List<Map<String, Object>> idleLines = allLines.stream()
                .filter(l -> isCaliberMatch(caliber, l.getCaliberLimit()))
                .map(l -> {
                    Map<String, Object> info = new HashMap<>();
                    info.put("lineId", l.getLineId());
                    info.put("caliberLimit", l.getCaliberLimit());
                    info.put("workshopId", l.getWorkshopId());
                    info.put("lineStatus", l.getLineStatus());
                    return info;
                }).collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("idleMachines", idleMachines);
        result.put("idleLines", idleLines);
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
