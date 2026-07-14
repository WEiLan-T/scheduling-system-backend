package com.company.scheduling.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "weaving_daily_logs")
public class WeavingDailyLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private String tapePartNumber;

    // 🌟 核心修复：补充缺失的带坯物理卷号字段
    private String tapeNumber;

    private String machineId;
    private BigDecimal capacityPerDay;
    private Boolean isDataNormal;
    private String remarks;
    private LocalDate entryDate;
    private BigDecimal totalDemand;
    private String enteredBy;
    private LocalDateTime createdAt = LocalDateTime.now();
}