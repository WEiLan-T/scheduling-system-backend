package com.company.scheduling.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 带坯分切请求体
 * 对应端点 POST /api/v1/workshops/integration/inventory/split
 */
@Data
public class TapeSplitRequest {
    private Long id;                  // 原库存记录ID（virtual_warehouse 主键）
    private List<BigDecimal> lengths; // 各子根长度（米），顺序即分切序号
}
