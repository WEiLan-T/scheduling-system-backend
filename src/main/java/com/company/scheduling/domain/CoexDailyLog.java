package com.company.scheduling.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 共挤车间日产量台账（字段对齐Excel表头：
 * 时间|机台号|产品类型|产品型号|颜色|主材|成品数量（根）|重量（公斤）|产能（米）|漏胶(Kg)）
 */
@Data
@Entity
@Table(name = "coex_daily_log", uniqueConstraints = {
        @UniqueConstraint(name = "uk_coex", columnNames = {"log_date", "machine_no", "product_model", "color"})
}, indexes = {
        @Index(name = "idx_coex_log_date", columnList = "log_date"),
        @Index(name = "idx_coex_model_log_date", columnList = "product_model, log_date")
})
public class CoexDailyLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "log_date", nullable = false)
    private LocalDate logDate;           // 时间

    @Column(name = "machine_no", nullable = false, length = 30)
    private String machineNo;            // 机台号（String，因为可能是"18#-2"等）

    @Column(name = "product_type", length = 50)
    private String productType;          // 产品类型

    @Column(name = "product_model", length = 100)
    private String productModel;         // 产品型号

    @Column(name = "color", length = 50)
    private String color;                // 颜色

    @Column(name = "main_material", length = 100)
    private String mainMaterial;         // 主材

    @Column(name = "finished_qty")
    private Integer finishedQty;         // 成品数量（根）

    @Column(name = "weight_kg", precision = 12, scale = 2)
    private BigDecimal weightKg;         // 重量（公斤）

    @Column(name = "capacity_meters", precision = 12, scale = 2)
    private BigDecimal capacityMeters;   // 产能（米）

    @Column(name = "leakage_kg", precision = 12, scale = 2)
    private BigDecimal leakageKg;        // 漏胶(Kg)

    @Column(name = "source_file_year")
    private Integer sourceFileYear;      // 来源文件年份

    @Column(name = "data_quality_flag", length = 20)
    private String dataQualityFlag;

    @Column(name = "data_source", length = 30)
    private String dataSource;

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

    /**
     * 兼容getter（供排产引擎调用）：旧字段capacityPerDay → 新字段capacityMeters
     * 非持久化字段，不参与JPA映射
     */
    @Transient
    public BigDecimal getCapacityPerDay() {
        return this.capacityMeters;
    }
}
