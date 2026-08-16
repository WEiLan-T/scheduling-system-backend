package com.company.scheduling.dto;

import lombok.Data;
import java.math.BigDecimal;

/**
 * 库存核对展示DTO（Excel值 vs DB计算值）
 */
@Data
public class InventoryReconciliationDTO {
    private Long id;            // 核对记录ID（前端确认操作需要）
    private String partNumber;
    private String tapeCode;
    private String modelSpec;
    private BigDecimal excelValue;      // Excel值
    private BigDecimal dbCalculatedValue; // DB计算值
    private BigDecimal difference;       // 差值
    private String status;              // ACCEPTED/PENDING
}
