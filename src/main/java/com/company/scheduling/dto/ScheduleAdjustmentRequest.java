package com.company.scheduling.dto;

import com.company.scheduling.domain.ProductionOrder;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class ScheduleAdjustmentRequest {
    private String orderId;

    // 🌟 新增：支持前端传入未落库的草稿订单数据，用于【模拟推演】
    private List<ProductionOrder> draftOrders;

    private List<ItemAdjustment> itemAdjustments;

    @Data
    public static class ItemAdjustment {
        private String finishedPartNumber;
        private BigDecimal manualWeavingChangeoverDays;
        private BigDecimal manualOperatorRatio;
        private BigDecimal manualCoexCapacity;
        private Integer manualStartDelayDays;
    }
}