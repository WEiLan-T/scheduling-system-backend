package com.company.scheduling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Entity
@Table(name = "machine_resources")
public class MachineResource {
    @Id
    @Column(name = "machine_id", length = 20)
    private String machineId;

    @Column(name = "workshop_name", nullable = false, length = 50)
    private String workshopName;

    @Column(name = "machine_type", nullable = false, length = 20)
    private String machineType; // WEAVING 或 COEXTRUSION

    @Column(name = "standard_daily_capacity", precision = 10, scale = 2)
    private BigDecimal standardDailyCapacity;

    @Column(name = "linked_machine_id", length = 20)
    private String linkedMachineId; // 绑定的下游/上游机台
}