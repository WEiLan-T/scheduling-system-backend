package com.company.scheduling.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class CoexEntryRequest {
    private Integer id;
    private LocalDate entryDate;
    private String lineId;

    // 👇 同步新增接收字段
    private String orderNumber;
    private String finishedPartNumber;
    private String semiFinishedNumber;   // 半成品编号
    private String finishedModelSpec;
    private String tapeNumber;
    private BigDecimal productionSpeed;

    private BigDecimal capacityPerDay;
    private String tapePartNumber;
    private BigDecimal tapeDemandQty;
    private Boolean isDataNormal;
    private String remarks;
    private String workshopId;
    private String caliberLimit;
    private String lineStatus;
}