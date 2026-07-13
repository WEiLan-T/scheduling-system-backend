package com.company.scheduling.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class ScheduleAdjustmentRequest {
    // 表头：目标排产订单号
    private String orderId;

    // 🌟 细粒度核心：对订单内的具体每个成品零件进行独立算法干预
    private List<ItemAdjustment> itemAdjustments;

    @Data
    public static class ItemAdjustment {
        private String finishedPartNumber;          // 目标成品零件号
        private BigDecimal manualWeavingChangeoverDays; // 单独指定：改机时间(天)
        private BigDecimal manualOperatorRatio;         // 单独指定：挡车工排班(台/人)
        private BigDecimal manualCoexCapacity;          // 单独指定：共挤产能(米/天)
        private Integer manualStartDelayDays;           // 单独指定：安全开机延时(天)
    }
}