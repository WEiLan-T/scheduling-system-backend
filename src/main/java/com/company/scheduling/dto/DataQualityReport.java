package com.company.scheduling.dto;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 数据质量分级报告（A级可信 / B级需验证 / C级需人工处理）
 */
@Data
public class DataQualityReport {
    private int totalRows;
    private int gradeACount;    // 可信数据
    private int gradeBCount;    // 需验证数据
    private int gradeCCount;    // 需人工处理
    private List<String> gradeBDetails = new ArrayList<>();   // B级数据详情
    private List<String> gradeCReasons = new ArrayList<>();   // C级数据拒绝原因
    private List<Map<String, Object>> cleanedData = new ArrayList<>();  // 清洗后的数据（A+B级）
}
