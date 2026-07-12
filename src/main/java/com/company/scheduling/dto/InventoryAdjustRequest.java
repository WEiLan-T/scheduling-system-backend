package com.company.scheduling.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class InventoryAdjustRequest {
    private LocalDate entryDate;     // 业务日期
    private String tapePartNumber;   // 带坯零件号
    private BigDecimal adjustMeters; // 调账米数 (正数增加，负数扣除)
    private String remarks;
}