package com.company.scheduling.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class CoexEntryRequest {
    private Integer id;
    private LocalDate entryDate;
    private String finishedPartNumber;
    private String lineId;
    private String workshopId;
    private String caliberLimit;
    private String lineStatus;
    private BigDecimal capacityPerDay;
    private Boolean isDataNormal;
    private BigDecimal tapeDemandQty;
    private String tapePartNumber;
    private String tapeNumber; // 🌟 新增：支持前端输入消耗的指定带坯编号
    private String remarks;
}