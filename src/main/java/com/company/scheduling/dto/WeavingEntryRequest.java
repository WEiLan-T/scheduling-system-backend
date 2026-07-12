package com.company.scheduling.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class WeavingEntryRequest {
    // 【基础业务信息】
    private LocalDate entryDate;     // 业务归属日期 (非常重要，可追溯历史)
    private String tapePartNumber;   // 带坯零件号

    // 【机台现场状态表数据】
    private String machineId;        // 机台号
    private String workshopId;       // 车间号
    private String warpSpec;         // 经线型号
    private String weftSpec;         // 纬线型号
    private Integer bobbinCount;     // 筒子数
    private String machineStatus;    // 机台状态 (在产/空闲等)
    private String caliberLimit;     // 口径限制
    private String adjacentMachine;  // 相邻机台
    private String operatorName;     // 挡车工

    // 【每日台账数据】
    private BigDecimal capacityPerDay; // 今日产能
    private Boolean isDataNormal;      // 数据是否正常
    private BigDecimal totalDemand;    // 需求总量
    private String remarks;            // 备注
}