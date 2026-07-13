package com.company.scheduling.dto;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class ScheduleAdjustmentRequest {
    private String orderId;

    // 织造车间人工干预参数
    private BigDecimal manualWeavingChangeoverDays; // 人工指定改机时间(天)
    private BigDecimal manualOperatorRatio;         // 人工指定挡车工负责机台数(台/人)

    // 共挤车间人工干预参数
    private BigDecimal manualCoexCapacity;          // 人工指定共挤产线产能
    private Integer manualStartDelayDays;           // 人工指定安全开机延迟缓冲(天)
}