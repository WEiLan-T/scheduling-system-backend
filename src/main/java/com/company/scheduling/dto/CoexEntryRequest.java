package com.company.scheduling.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class CoexEntryRequest {
    // 【基础业务信息】
    private LocalDate entryDate;
    private String finishedPartNumber; // 成品零件号

    // 【产线现场状态表数据】
    private String lineId;             // 产线号
    private String workshopId;         // 车间号
    private String caliberLimit;       // 口径限制
    private String lineStatus;         // 产线状态

    // 【每日台账数据】
    private BigDecimal capacityPerDay; // 今日产能
    private Boolean isDataNormal;      // 数据是否正常
    private BigDecimal tapeDemandQty;  // 消耗带坯量 (用于自动扣除库存)
    private String tapePartNumber;     // 消耗的带坯号
    private String remarks;
}