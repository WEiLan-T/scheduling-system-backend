package com.company.scheduling.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class InquiryRequest {
    // 产品明细列表（不需要订单号）
    private List<InquiryItem> items;
    // 拟生产天数（替代交货期）
    private Integer plannedProductionDays;
    // 全局参数
    private Integer globalBufferDays = 3;
    private Integer weavingAdvanceDays = 2;
    // 织造储备库存天数（null/0 = 不储备，与现状完全一致）
    private Integer weavingReserveDays;
    // 人工调整参数（可选）
    private List<ItemResourceOverride> resourceOverrides;

    @Data
    public static class InquiryItem {
        private String finishedPartNumber;
        private String productName;
        private String modelSpec;
        private BigDecimal totalLength;        // 总需求米数
        private BigDecimal metersPerRoll;
        private Integer rollCount;
    }

    @Data
    public static class ItemResourceOverride {
        private String finishedPartNumber;
        private Integer machineCount;          // 手动指定机台数
        private Integer lineCount;             // 手动指定产线数
        private List<String> assignedMachineIds;
        private List<String> assignedLineIds;
        private BigDecimal manualWeavingCapacity;
        private BigDecimal manualCoexCapacity;
    }
}
