package com.company.scheduling.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.UUID;

@Data
public class DailyLogRequest {
    // 操作工人ID
    private String workerId;

    // 机台ID
    private String machineId;

    // 录入类型 (PRODUCTION: 生产, CONSUMPTION: 消耗)
    private String inputType;

    // 数量
    private BigDecimal qty;

    // 关联的带坯/零件号
    private UUID tapePartNumber;
}