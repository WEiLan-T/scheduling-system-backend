package com.company.scheduling.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "coex_daily_log")
public class CoexDailyLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private LocalDate entryDate;
    private String lineId;

    // 👇 完美对接车间产能明细表格的专属字段
    private String orderNumber;          // 订单号
    private String finishedPartNumber;   // 成品零件号
    private String semiFinishedNumber;   // 半成品编号 (新增)
    private String finishedModelSpec;    // 成品规格型号
    private String tapeNumber;           // 带坯编号

    @Column(precision = 10, scale = 4)
    private BigDecimal productionSpeed;  // 生产速度（m/s）

    // MES 库存控制相关字段
    private BigDecimal capacityPerDay;   // 共挤成品长度(米)
    private String tapePartNumber;       // 带坯零件号(系统反查得出)
    private BigDecimal tapeDemandQty;    // 带坯消耗长度(米)

    private Boolean isDataNormal;
    private String remarks;
    private String workshopId;

    private String enteredBy;
    private LocalDateTime createdAt = LocalDateTime.now();
}