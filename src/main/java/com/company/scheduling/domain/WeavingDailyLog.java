package com.company.scheduling.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 织带车间日产量台账（字段对齐Excel表头：
 * 零件号|年|月|日|机台号|带坯编号|型号规格|经线|纬线|班次|姓名|当班产量|标准产能|标准小时|标准小时产能|绩效工时|备注）
 */
@Data
@Entity
@Table(name = "weaving_daily_log", uniqueConstraints = {
        @UniqueConstraint(name = "uk_weaving", columnNames = {"entry_year", "entry_month", "entry_day", "machine_no", "shift_type", "tape_code", "model_spec"})
}, indexes = {
        @Index(name = "idx_weaving_entry_date", columnList = "entry_date"),
        @Index(name = "idx_weaving_pn_entry_date", columnList = "part_number, entry_date"),
        @Index(name = "idx_weaving_machine_no", columnList = "machine_no"),
        @Index(name = "idx_weaving_tape_code", columnList = "tape_code")
})
public class WeavingDailyLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "part_number", length = 50)
    private String partNumber;          // 零件号

    @Column(name = "entry_year", nullable = false)
    private Integer entryYear;          // 年（4位）

    @Column(name = "entry_month", nullable = false)
    private Integer entryMonth;         // 月

    @Column(name = "entry_day", nullable = false)
    private Integer entryDay;           // 日

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;        // 合并日期（用于查询索引）

    @Column(name = "machine_no", nullable = false)
    private Integer machineNo;          // 机台号

    @Column(name = "tape_code", length = 50)
    private String tapeCode;            // 带坯编号

    @Column(name = "model_spec", nullable = false, length = 100)
    private String modelSpec;           // 型号规格

    @Column(name = "warp_thread", length = 200)
    private String warpThread;          // 经线

    @Column(name = "weft_thread", length = 200)
    private String weftThread;          // 纬线

    @Column(name = "shift_type", nullable = false, length = 10)
    private String shiftType;           // 班次（白/夜）

    @Column(name = "worker_name", length = 50)
    private String workerName;          // 姓名

    @Column(name = "shift_output", precision = 12, scale = 2)
    private BigDecimal shiftOutput;     // 当班产量

    @Column(name = "standard_capacity", precision = 12, scale = 2)
    private BigDecimal standardCapacity; // 标准产能

    @Column(name = "standard_hours", precision = 6, scale = 2)
    private BigDecimal standardHours;   // 标准小时

    @Column(name = "standard_hour_capacity", precision = 12, scale = 4)
    private BigDecimal standardHourCapacity; // 标准小时产能

    @Column(name = "performance_hours", precision = 12, scale = 4)
    private BigDecimal performanceHours;     // 绩效工时

    @Column(name = "remark", length = 500)
    private String remark;              // 备注

    @Column(name = "warp_weight_per_meter", precision = 10, scale = 4)
    private BigDecimal warpWeightPerMeter;          // 经线米重g/m

    @Column(name = "weft_weight_per_meter_2000d", precision = 10, scale = 4)
    private BigDecimal weftWeightPerMeter2000D;     // 纬线米重2000D g/m

    @Column(name = "weft_weight_per_meter_3000d", precision = 10, scale = 4)
    private BigDecimal weftWeightPerMeter3000D;     // 纬线米重3000D g/m

    @Column(name = "warp_usage_kg_per_meter", precision = 10, scale = 4)
    private BigDecimal warpUsageKgPerMeter;         // 经线耗用kg/m

    @Column(name = "weft_usage_kg_per_meter_2000d", precision = 10, scale = 4)
    private BigDecimal weftUsageKgPerMeter2000D;    // 纬线耗用2000D kg/m

    @Column(name = "weft_usage_kg_per_meter_3000d", precision = 10, scale = 4)
    private BigDecimal weftUsageKgPerMeter3000D;    // 纬线耗用3000D kg/m

    @Column(name = "data_quality_flag", length = 20)
    private String dataQualityFlag;     // A/B/C级标记，默认NORMAL

    @Column(name = "data_source", length = 30)
    private String dataSource;          // 来源：EXCEL_IMPORT/MANUAL

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.dataQualityFlag == null) {
            this.dataQualityFlag = "NORMAL";
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
