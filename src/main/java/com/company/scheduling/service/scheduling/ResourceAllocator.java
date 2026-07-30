package com.company.scheduling.service.scheduling;

import com.company.scheduling.domain.CoexLineStatus;
import com.company.scheduling.domain.EstimatedProductionSchedule;
import com.company.scheduling.domain.WeavingMachineStatus;
import com.company.scheduling.repository.EstimatedProductionScheduleRepo;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 资源分配器：管理排产资源时间线
 * 使用 ResourceTimeline 作为纯数据容器，避免有状态 Bean 问题
 */
@Component
public class ResourceAllocator {

    /**
     * 创建一个新的资源时间线实例（每次排产请求独立使用）
     */
    public ResourceTimeline createTimeline() {
        return new ResourceTimeline();
    }

    /**
     * 从已有排产记录初始化资源时间线
     */
    public ResourceTimeline initFromExistingSchedules(
            List<WeavingMachineStatus> machines,
            List<CoexLineStatus> lines,
            EstimatedProductionScheduleRepo scheduleRepo,
            LocalDateTime now) {

        ResourceTimeline timeline = new ResourceTimeline();

        for (WeavingMachineStatus wm : machines) {
            List<EstimatedProductionSchedule> existing = scheduleRepo.findByWeavingMachineIdAndWeavingEndDateAfter(wm.getMachineId(), now);
            existing.stream()
                .filter(e -> e.getWeavingEndDate() != null)
                .max(Comparator.comparing(EstimatedProductionSchedule::getWeavingEndDate))
                .ifPresent(e -> timeline.updateMachineTimeline(wm.getMachineId(), e.getWeavingEndDate()));
        }

        for (CoexLineStatus cl : lines) {
            List<EstimatedProductionSchedule> existing = scheduleRepo.findByCoexLineIdAndCoexEndDateAfter(cl.getLineId(), now);
            existing.stream()
                .filter(e -> e.getCoexEndDate() != null)
                .max(Comparator.comparing(EstimatedProductionSchedule::getCoexEndDate))
                .ifPresent(e -> timeline.updateLineTimeline(cl.getLineId(), e.getCoexEndDate()));
        }

        return timeline;
    }

    /**
     * 纯数据容器：资源占用时间线（每次排产请求独立实例）
     */
    public static class ResourceTimeline {
        private final Map<String, LocalDateTime> machineTimeline = new HashMap<>();
        private final Map<String, LocalDateTime> lineTimeline = new HashMap<>();
        private final List<String> conflictWarnings = new ArrayList<>();

        public LocalDateTime getMachineAvailableTime(String machineId, LocalDateTime now) {
            return machineTimeline.getOrDefault("W_" + machineId, now);
        }

        public LocalDateTime getLineAvailableTime(String lineId, LocalDateTime now) {
            return lineTimeline.getOrDefault("C_" + lineId, now);
        }

        public void updateMachineTimeline(String machineId, LocalDateTime endDate) {
            machineTimeline.put("W_" + machineId, endDate);
        }

        public void updateLineTimeline(String lineId, LocalDateTime endDate) {
            lineTimeline.put("C_" + lineId, endDate);
        }

        public List<String> getConflictWarnings() {
            return conflictWarnings;
        }

        public void addConflictWarning(String warning) {
            conflictWarnings.add(warning);
        }
    }
}
