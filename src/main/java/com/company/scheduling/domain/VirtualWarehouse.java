package com.company.scheduling.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 虚拟仓库库存（支持按月快照与差值核对）
 */
@Data
@Entity
@Table(name = "virtual_warehouse", uniqueConstraints = {
        @UniqueConstraint(name = "uk_warehouse", columnNames = {"part_number", "tape_code", "snapshot_date"})
}, indexes = {
        @Index(name = "idx_warehouse_snapshot_date", columnList = "snapshot_date")
})
public class VirtualWarehouse {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "part_number", nullable = false, length = 50)
    private String partNumber;           // 带坯零件号

    @Column(name = "model_spec", length = 100)
    private String modelSpec;            // 型号规格

    @Column(name = "warp_thread", length = 200)
    private String warpThread;           // 经线型号

    @Column(name = "weft_thread", length = 200)
    private String weftThread;           // 纬线型号

    @Column(name = "tape_code", length = 50)
    private String tapeCode;             // 带坯编号

    @Column(name = "stock_meters", precision = 12, scale = 2)
    private BigDecimal stockMeters;      // 库存数量（米）

    @Column(name = "stock_type", length = 30)
    private String stockType;            // 库存类型：订单/库存/滞留

    @Column(name = "remark", length = 500)
    private String remark;

    @Column(name = "snapshot_date")
    private LocalDate snapshotDate;      // 快照日期

    @Column(name = "reconcile_status", length = 20)
    private String reconcileStatus;      // PENDING/RECONCILED

    @Column(name = "last_reconcile_date")
    private LocalDate lastReconcileDate;

    @Column(name = "data_quality_flag", length = 20)
    private String dataQualityFlag;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.reconcileStatus == null) {
            this.reconcileStatus = "PENDING";
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 兼容getter（供排产引擎调用）：旧字段currentStockMeters → 新字段stockMeters
     * 非持久化字段，不参与JPA映射
     */
    @Transient
    public BigDecimal getCurrentStockMeters() {
        return this.stockMeters;
    }
}
