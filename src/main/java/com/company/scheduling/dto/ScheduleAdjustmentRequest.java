package com.company.scheduling.dto;

import com.company.scheduling.domain.ProductionOrder;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class ScheduleAdjustmentRequest {
    private String orderId;
    private List<ProductionOrder> draftOrders;
    private List<ItemAdjustment> itemAdjustments;

    // 🌟 新增：人工调整接口（全局配置）
    private Integer globalBufferDays = 3;     // 默认所有订单留出 3 天缓冲期
    private Integer weavingAdvanceDays = 2;   // 默认织造比共挤提前 2 天结束
    private Integer weavingReserveDays;        // 织造储备库存天数（null/0 = 不储备，与现状完全一致）

    @Data
    public static class ItemAdjustment {
        private String finishedPartNumber;
        private BigDecimal manualWeavingChangeoverDays;
        private BigDecimal manualCoexCapacity;
        private Integer manualStartDelayDays;
        private BigDecimal manualWeavingCapacity;
        private Integer manualWeavingMachineCount;  // 手动指定织造机台数
        private Integer manualCoexLineCount;        // 手动指定共挤产线数
        private List<String> assignedMachineIds;    // 指定织造机台ID列表
        private List<String> assignedLineIds;       // 指定共挤产线ID列表
    }
}