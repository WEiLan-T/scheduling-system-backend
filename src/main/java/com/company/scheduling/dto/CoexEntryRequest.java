package com.company.scheduling.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class CoexEntryRequest {
    private Integer id; // 🌟 新增：记录ID，用于支持数据的修改

    // 【以下保留您原本的所有字段，不要删除】
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
    private String remarks;
}