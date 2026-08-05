package com.company.scheduling.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data @Entity @Table(name = "production_orders", indexes = {
        @Index(name = "idx_po_order_id", columnList = "orderId"),
        @Index(name = "idx_po_finished_part_number", columnList = "finishedPartNumber")
})
public class ProductionOrder {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private String orderId;              // 订单号
    private String customerName;         // 客户名称
    private String salesperson;          // 销售员

    private LocalDate orderDate;         // 订单下达时间
    private LocalDate deliveryDate;      // 交货期

    private String finishedPartNumber;   // 零件号
    private String productName;          // 品名
    private String modelSpec;            // 规格型号
    private String color;                // 胶色

    @Column(precision = 10, scale = 2)
    private BigDecimal unfinishedMeters; // 🌟 新增：未入库完成米数

    @Column(precision = 10, scale = 2)
    private BigDecimal metersPerRoll;    // 单卷长度
    private Integer rollCount;           // 卷数

    @Column(precision = 10, scale = 2)
    private BigDecimal totalLength;      // 总数量(米)

    private String remarks;              // 备注
    private String enteredBy;
    private LocalDateTime createdAt = LocalDateTime.now();
}