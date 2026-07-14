package com.company.scheduling.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class WeavingEntryRequest {
    private Integer id;
    private LocalDate entryDate;
    private String tapePartNumber;
    private String tapeNumber; // 🌟 新增：支持前端传入带坯编号
    private String machineId;
    private String workshopId;
    private String warpSpec;
    private String weftSpec;
    private Integer bobbinCount;
    private String machineStatus;
    private String caliberLimit;
    private String adjacentMachine;
    private String operatorName;
    private BigDecimal capacityPerDay;
    private Boolean isDataNormal;
    private BigDecimal totalDemand;
    private String remarks;
}