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

    // 👇 新增6字段：米重/耗用（手工录入可选填）
    private BigDecimal warpWeightPerMeter;          // 经线米重g/m
    private BigDecimal weftWeightPerMeter2000D;     // 纬线米重2000D g/m
    private BigDecimal weftWeightPerMeter3000D;     // 纬线米重3000D g/m
    private BigDecimal warpUsageKgPerMeter;         // 经线耗用kg/m
    private BigDecimal weftUsageKgPerMeter2000D;    // 纬线耗用2000D kg/m
    private BigDecimal weftUsageKgPerMeter3000D;    // 纬线耗用3000D kg/m

    private Boolean isDataNormal;
    private String remarks;
    private BigDecimal totalDemand;
    private String workshopId;
    private Integer bobbinCount;
    private String machineStatus;
    private Integer caliberMin;
    private Integer caliberMax;
    private String adjacentMachine;
}