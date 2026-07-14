package com.company.scheduling.domain;
import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data @Entity @Table(name = "estimated_production_schedule")
public class EstimatedProductionSchedule {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    private String planId;
    private String orderId;
    private String finishedPartNumber;
    private String tapePartNumber;
    private LocalDate weavingStartDate;
    private LocalDate weavingEndDate;
    private LocalDate coexStartDate;
    private LocalDate coexEndDate;
    private BigDecimal estimatedTotalDays;
    // 👇 新增：用于持久化保存计划员在草稿中分配的具体机台和产线
    private String weavingMachineId;
    private String coexLineId;
    private String enteredBy;
    private LocalDateTime createdAt = LocalDateTime.now();
}
