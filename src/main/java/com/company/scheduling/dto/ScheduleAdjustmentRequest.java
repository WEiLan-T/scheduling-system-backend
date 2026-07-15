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

    @Data
    public static class ItemAdjustment {
        private String finishedPartNumber;

        // 👇 核心修复：排产算法所需的人工干预推演参数
        private BigDecimal manualWeavingChangeoverDays;
        private BigDecimal manualCoexCapacity;
        private Integer manualStartDelayDays;
    }
}