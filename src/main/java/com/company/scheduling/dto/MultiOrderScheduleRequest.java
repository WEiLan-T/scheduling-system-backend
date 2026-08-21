package com.company.scheduling.dto;

import lombok.Data;
import java.util.List;

@Data
public class MultiOrderScheduleRequest {
    private List<String> orderIds;
    private Integer globalBufferDays = 3;
    private Integer weavingReserveDays; // 织造储备库存天数（null/0 = 不储备，与现状完全一致）
    private List<ScheduleAdjustmentRequest.ItemAdjustment> itemAdjustments;
}
