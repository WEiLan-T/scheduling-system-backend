package com.company.scheduling.service;

import com.company.scheduling.domain.MachineActiveTask;
import com.company.scheduling.domain.MachineResource;
import com.company.scheduling.repository.MachineActiveTaskRepo;
import com.company.scheduling.repository.MachineResourceRepo;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

@Service
public class EstimationService {

    private final MachineResourceRepo resourceRepo;
    private final MachineActiveTaskRepo activeTaskRepo;

    public EstimationService(MachineResourceRepo resourceRepo, MachineActiveTaskRepo activeTaskRepo) {
        this.resourceRepo = resourceRepo;
        this.activeTaskRepo = activeTaskRepo;
    }

    /**
     * 联动动态产能与排队时间估算引擎 (综合织造与共挤)
     */
    public String calculateDynamicCompletionTime(String machineId, BigDecimal targetQty, BigDecimal customCapacity) {
        // 1. 获取主查询机台
        Optional<MachineResource> mainMachineOpt = resourceRepo.findById(machineId);
        if (mainMachineOpt.isEmpty()) {
            return "错误：未找到机台 " + machineId + " 的档案信息。";
        }
        MachineResource mainMachine = mainMachineOpt.get();

        // 2. 梳理桥架联动关系 (寻找流水线上的搭档)
        MachineResource weavingMachine = null;
        MachineResource coexMachine = null;

        if ("WEAVING".equals(mainMachine.getMachineType())) {
            weavingMachine = mainMachine;
            // 找找看有没有共挤机绑定了这台织造机
            coexMachine = resourceRepo.findByLinkedMachineId(mainMachine.getMachineId()).orElse(null);
        } else if ("COEXTRUSION".equals(mainMachine.getMachineType())) {
            coexMachine = mainMachine;
            // 找出这台共挤机上游绑定的织造机
            if (mainMachine.getLinkedMachineId() != null) {
                weavingMachine = resourceRepo.findById(mainMachine.getLinkedMachineId()).orElse(null);
            }
        }

        StringBuilder report = new StringBuilder();
        report.append(String.format("【智能排产估算报告：%s 联动模式】\n", mainMachine.getMachineId()));
        report.append(String.format("> 订单目标：%s 单位\n", targetQty));

        if (weavingMachine != null && coexMachine != null) {
            report.append(String.format("> 涉及产线：织造 [%s] -> 共挤 [%s] (桥架直供)\n", weavingMachine.getMachineId(), coexMachine.getMachineId()));
        } else {
            report.append(String.format("> 涉及产线：单机生产 [%s]\n", mainMachine.getMachineId()));
        }

        report.append("--- [1. 排队侦测] ---\n");
        // 3. 计算各个机台的等待时间
        BigDecimal weavingWait = calculateMachineWaitTime(weavingMachine, report);
        BigDecimal coexWait = calculateMachineWaitTime(coexMachine, report);

        // 核心逻辑：流水线开工必须等最慢的那台机器空出来
        BigDecimal maxWaitTime = weavingWait.max(coexWait);
        report.append(String.format(">> 流水线最长需等待：%s 天空出\n", maxWaitTime));

        report.append("--- [2. 产能评估] ---\n");
        // 4. 计算生产速度 (木桶效应)
        BigDecimal weavingSpeed = getWorkingCapacity(weavingMachine, mainMachine.getMachineId(), customCapacity, report);
        BigDecimal coexSpeed = getWorkingCapacity(coexMachine, mainMachine.getMachineId(), customCapacity, report);

        BigDecimal pipelineSpeed = weavingSpeed; // 默认织造速度
        if (weavingMachine != null && coexMachine != null) {
            pipelineSpeed = weavingSpeed.min(coexSpeed); // 如果有桥架联动，取两者中较小的一个 (瓶颈产能)
        } else if (coexMachine != null) {
            pipelineSpeed = coexSpeed;
        }
        report.append(String.format(">> 综合瓶颈流水线产能：%s 单位/天\n", pipelineSpeed));

        report.append("--- [3. 完工估算] ---\n");
        // 5. 计算纯生产时间与最终完工时间
        if (pipelineSpeed.compareTo(BigDecimal.ZERO) == 0) {
            return report.append("错误：流水线产能为 0，无法估算。").toString();
        }

        BigDecimal productionDays = targetQty.divide(pipelineSpeed, 2, RoundingMode.HALF_UP);
        BigDecimal totalDays = maxWaitTime.add(productionDays);

        report.append(String.format(">> 纯生产耗时：%s / %s = %s 天\n", targetQty, pipelineSpeed, productionDays));
        report.append(String.format("🌟 最终参考：预计将在 %s 天后（含排队 %s 天），全线结束生产完工。", totalDays, maxWaitTime));

        return report.toString();
    }

    /**
     * 子方法：计算单台机器的排队等待时间
     */
    private BigDecimal calculateMachineWaitTime(MachineResource machine, StringBuilder report) {
        if (machine == null) return BigDecimal.ZERO;

        List<MachineActiveTask> activeTasks = activeTaskRepo.findByMachineId(machine.getMachineId());
        if (activeTasks.isEmpty()) {
            report.append(String.format("机台 %s：[空闲] 等待 0 天\n", machine.getMachineId()));
            return BigDecimal.ZERO;
        }

        // 为了简化，假设当前只有1个任务在运行
        MachineActiveTask currentTask = activeTasks.get(0);
        BigDecimal remainingQty = currentTask.getTargetQty().subtract(currentTask.getProducedQty());
        BigDecimal speed = currentTask.getCurrentDailySpeed();

        if (speed.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;

        BigDecimal waitDays = remainingQty.divide(speed, 2, RoundingMode.HALF_UP);
        report.append(String.format("机台 %s：[忙碌] 正在执行零件(%s)，剩余 %s，需排队 %s 天\n",
                machine.getMachineId(), currentTask.getPartNumber(), remainingQty, waitDays));

        return waitDays;
    }

    /**
     * 子方法：获取计算使用的产能 (人工干预 > 历史 > 标准)
     */
    private BigDecimal getWorkingCapacity(MachineResource machine, String requestedMachineId, BigDecimal customCapacity, StringBuilder report) {
        if (machine == null) return BigDecimal.ZERO;

        // 如果用户手工传入了产能，并且刚好就是这台主查询机器，则采纳人工输入
        if (customCapacity != null && customCapacity.compareTo(BigDecimal.ZERO) > 0 && machine.getMachineId().equals(requestedMachineId)) {
            report.append(String.format("机台 %s：采用手工干预产能 %s 单位/天\n", machine.getMachineId(), customCapacity));
            return customCapacity;
        }

        // TODO: 未来可在此处查询 daily_data_entry_log 提取历史平均 AVG()。目前先使用标准产能兜底。
        BigDecimal standardCap = machine.getStandardDailyCapacity();
        report.append(String.format("机台 %s：采用标准/历史产能 %s 单位/天\n", machine.getMachineId(), standardCap));
        return standardCap != null ? standardCap : new BigDecimal("250.00");
    }
}