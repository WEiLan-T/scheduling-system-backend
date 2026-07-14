package com.company.scheduling.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "coex_daily_logs")
public class CoexDailyLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private String finishedPartNumber;
    private String lineId;
    private BigDecimal capacityPerDay;
    private Boolean isDataNormal;
    private String remarks;
    private BigDecimal tapeDemandQty;

    private String tapePartNumber;

    // 🌟 核心修复：补充缺失的所耗带坯物理卷号字段
    private String tapeNumber;

    private LocalDate entryDate;
    private String enteredBy;
    private LocalDateTime createdAt = LocalDateTime.now();
}