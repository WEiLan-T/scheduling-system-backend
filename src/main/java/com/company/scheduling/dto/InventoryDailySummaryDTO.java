package com.company.scheduling.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 日库存推算结果（按 tapeCode 维度）
 * 推算公式：某日库存 = 最近一期月度权威快照 + Σ织造台账产量(锚点日之后至目标日) − Σ共挤台账消耗(锚点日之后至目标日)
 */
@Data
public class InventoryDailySummaryDTO {

    private LocalDate date;               // 推算目标日期
    private String partNumber;            // 带坯零件号
    private String tapeCode;              // 带坯编号
    private LocalDate anchorDate;         // 月度锚点快照日期（最近一期不超过目标日的快照）
    private BigDecimal anchorStock;       // 锚点快照库存值
    private BigDecimal weavingAdded;      // 锚点后至目标日的织造产量合计
    private BigDecimal coexConsumed;      // 锚点后至目标日的共挤消耗合计
    private BigDecimal estimatedStock;    // 推算日库存 = anchorStock + weavingAdded - coexConsumed
}
