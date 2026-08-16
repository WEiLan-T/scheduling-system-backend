package com.company.scheduling.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 库存核对记录（Excel值与DB自动计算值的差值核对）
 */
@Data
@Entity
@Table(name = "inventory_reconciliation", indexes = {
        @Index(name = "idx_reconcile_batch", columnList = "import_batch_id")
})
public class InventoryReconciliation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @Column(name = "part_number", nullable = false, length = 50)
    private String partNumber;

    @Column(name = "tape_code", length = 50)
    private String tapeCode;

    @Column(name = "excel_value", precision = 12, scale = 2)
    private BigDecimal excelValue;       // Excel中的值

    @Column(name = "db_calculated_value", precision = 12, scale = 2)
    private BigDecimal dbCalculatedValue; // DB自动计算值

    @Column(name = "difference", precision = 12, scale = 2)
    private BigDecimal difference;       // 差值 = excel - db

    @Column(name = "reconcile_status", length = 20)
    private String reconcileStatus;      // ACCEPTED/PENDING

    @Column(name = "import_batch_id", length = 50)
    private String importBatchId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.reconcileStatus == null) {
            this.reconcileStatus = "PENDING";
        }
    }
}
