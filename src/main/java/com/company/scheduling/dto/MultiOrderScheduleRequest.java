package com.company.scheduling.dto;

import lombok.Data;
import java.util.List;

@Data
public class MultiOrderScheduleRequest {
    private List<String> orderIds;
    private Integer globalBufferDays = 3;
    private Integer weavingAdvanceDays = 2;
    private List<ScheduleAdjustmentRequest.ItemAdjustment> itemAdjustments;
}
