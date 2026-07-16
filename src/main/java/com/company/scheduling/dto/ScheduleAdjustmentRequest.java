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
        private BigDecimal manualWeavingChangeoverDays;
        private BigDecimal manualCoexCapacity;
        private Integer manualStartDelayDays;

        // 👇 新增：用于兜底系统无法查到历史平均产能时的手工录入
        private BigDecimal manualWeavingCapacity;
    }
}