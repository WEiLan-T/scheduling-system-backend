package com.company.scheduling.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "product_process", indexes = {
        @Index(name = "idx_pp_tape_part_number", columnList = "tape_part_number")
})
public class ProductProcess {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(unique = true, nullable = false)
    private String finishedPartNumber; // 成品零件号 (主键/索引)

    @Column(name = "tape_part_number", nullable = false)
    private String tapePartNumber;     // 对应的带坯零件号

    private String finishedModelSpec;  // 成品规格型号
    private String tapeModelSpec;      // 带坯规格型号
    private String warpSpec;           // 织造经线型号 (工艺要求)
    private String weftSpec;           // 织造纬线型号（3000D为默认）(工艺要求)

    private String materialType;       // 材料类型

    @Column(precision = 12, scale = 2)
    private BigDecimal coexMaxDailyOutput;          // 共挤最大日产

    @Column(precision = 12, scale = 2)
    private BigDecimal weavingStandardDailyOutput;  // 织造标准日产

    private String weftSpec3000D;      // 织造纬线型号3000D
    private String weftSpec2000D;      // 织造纬线型号2000D

    @Column(precision = 10, scale = 4)
    private BigDecimal warpWeightPerMeter;          // 经线米重g/m

    @Column(precision = 10, scale = 4)
    private BigDecimal weftWeightPerMeter3000D;     // 纬线米重3000D g/m

    @Column(precision = 10, scale = 4)
    private BigDecimal weftWeightPerMeter2000D;     // 纬线米重2000D g/m

    @Column(precision = 10, scale = 4)
    private BigDecimal glueUsagePerMeter;           // 用胶量kg/m

    private String enteredBy;
    private LocalDateTime createdAt = LocalDateTime.now();
}