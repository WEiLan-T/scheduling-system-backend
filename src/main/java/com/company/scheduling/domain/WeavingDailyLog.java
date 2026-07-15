package com.company.scheduling.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "weaving_daily_log")
public class WeavingDailyLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private LocalDate entryDate;
    private String machineId;
    private String tapePartNumber;
    private String tapeNumber;
    private BigDecimal capacityPerDay;

    // 👇 为完美契合产能明细 Excel，新增以下专用字段
    private String modelSpec;               // 型号规格
    private String warpSpec;                // 经线
    private String weftSpec;                // 纬线
    private String shift;                   // 班次 (白/夜)
    private String operatorName;            // 姓名 (操作工)

    @Column(precision = 10, scale = 4)
    private BigDecimal standardCapacity;    // 标准产能
    @Column(precision = 10, scale = 4)
    private BigDecimal standardHours;       // 标准小时
    @Column(precision = 10, scale = 4)
    private BigDecimal standardHourlyCapacity; // 标准小时产能
    @Column(precision = 10, scale = 4)
    private BigDecimal performanceHours;    // 绩效工时

    private Boolean isDataNormal;
    private String remarks;
    private BigDecimal totalDemand;
    private String workshopId;

    private String enteredBy;
    private LocalDateTime createdAt = LocalDateTime.now();
}