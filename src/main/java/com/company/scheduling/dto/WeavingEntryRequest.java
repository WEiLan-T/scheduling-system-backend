package com.company.scheduling.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class WeavingEntryRequest {
    private Integer id; // 🌟 新增：记录ID，用于支持数据的修改

    // 【以下保留您原本的所有字段，不要删除】
    private LocalDate entryDate;
    private String tapePartNumber;
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