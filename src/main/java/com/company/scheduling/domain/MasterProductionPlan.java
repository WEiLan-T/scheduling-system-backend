package com.company.scheduling.domain;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data @Entity @Table(name = "master_production_plan")
public class MasterProductionPlan {
    @Id private String planId;
    private String orderId;
    private String tapePartNumber;
    private String finishedPartNumber;
    private String machineId;
    private String lineId;
    private String enteredBy;
    private LocalDateTime createdAt = LocalDateTime.now();
}