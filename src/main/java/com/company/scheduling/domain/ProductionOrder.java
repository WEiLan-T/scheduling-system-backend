package com.company.scheduling.domain;
import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data @Entity @Table(name = "production_orders")
public class ProductionOrder {
    @Id private String orderId;
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