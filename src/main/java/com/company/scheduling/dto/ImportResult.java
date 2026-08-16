package com.company.scheduling.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

/**
 * Excel导入结果（含数据质量报告与导入批次号）
 */
@Data
public class ImportResult {
    private int totalRows;      // 总行数
    private int insertedCount;  // 新增行数
    private int skippedCount;   // 跳过行数（已存在）
    private int rejectedCount;  // 拒绝行数（C级）
    private DataQualityReport qualityReport;  // 数据质量报告
    private String importBatchId;  // 导入批次号
    private String message;

    /**
     * 内部载荷字段：本次导入实际新增的台账实体列表（供 Controller 触发异步库存同步时透传）。
     * @JsonIgnore 保证不出现在 HTTP 响应中，其余字段结构不变
     */
    @JsonIgnore
    private transient Object insertedPayload;
}
