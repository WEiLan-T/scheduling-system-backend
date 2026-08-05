package com.company.scheduling.domain;
import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data @Entity @Table(name = "estimated_production_schedule", indexes = {
        @Index(name = "idx_eps_order_id", columnList = "orderId"),
        @Index(name = "idx_eps_weaving_machine_id", columnList = "weavingMachineId"),
        @Index(name = "idx_eps_coex_line_id", columnList = "coexLineId"),
        @Index(name = "idx_eps_weaving_end_date", columnList = "weavingEndDate"),
        @Index(name = "idx_eps_coex_end_date", columnList = "coexEndDate")
})
public class EstimatedProductionSchedule {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    private String planId;
    private String orderId;
    private String finishedPartNumber;
    private String tapePartNumber;

    // 👇 精度升级为 LocalDateTime
    private LocalDateTime weavingStartDate;
    private LocalDateTime weavingEndDate;
    private LocalDateTime coexStartDate;
    private LocalDateTime coexEndDate;

    private BigDecimal estimatedTotalDays;
    private String weavingMachineId;
    private String coexLineId;
    private String enteredBy;
    private LocalDateTime createdAt = LocalDateTime.now();
}