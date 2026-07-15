package com.company.scheduling.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class WeavingEntryRequest {
    private Integer id;
    private LocalDate entryDate;
    private String machineId;
    private String tapePartNumber;
    private String tapeNumber;
    private BigDecimal capacityPerDay;

    // 👇 同步新增对应字段
    private String modelSpec;
    private String warpSpec;
    private String weftSpec;
    private String shift;
    private String operatorName;
    private BigDecimal standardCapacity;
    private BigDecimal standardHours;
    private BigDecimal standardHourlyCapacity;
    private BigDecimal performanceHours;

    private Boolean isDataNormal;
    private String remarks;
    private BigDecimal totalDemand;
    private String workshopId;
    private Integer bobbinCount;
    private String machineStatus;
    private String caliberLimit;
    private String adjacentMachine;
}