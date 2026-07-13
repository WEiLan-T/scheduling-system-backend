package com.company.scheduling.domain;
import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data @Entity @Table(name = "production_orders")
public class ProductionOrder {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id; // 🌟 修复：使用自增 ID 作为主键，支持一单多品

    private String orderId;
    private LocalDate deliveryDate;
    private LocalDate orderDate;
    private String placerName;
    private String finishedPartNumber;
    private String modelSpec;
    private String material;
    private BigDecimal metersPerRoll;
    private Integer rollCount;
    private String remarks;
    private String enteredBy;
    private LocalDateTime createdAt = LocalDateTime.now();
}