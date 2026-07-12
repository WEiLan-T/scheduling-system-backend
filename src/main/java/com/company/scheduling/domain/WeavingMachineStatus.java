package com.company.scheduling.domain;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data @Entity @Table(name = "weaving_machine_status")
public class WeavingMachineStatus {
    @Id private String machineId;
    private String workshopId;
    private String warpSpec;
    private String weftSpec;
    private Integer bobbinCount;
    private String machineStatus;
    private String caliberLimit;
    private String adjacentMachine;
    private String operatorName;
    private String enteredBy;
    private LocalDateTime createdAt = LocalDateTime.now();
}