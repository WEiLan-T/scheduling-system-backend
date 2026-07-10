package com.company.scheduling.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Entity
@Table(name = "machine_active_tasks")
public class MachineActiveTask {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "task_id")
    private Integer taskId;

    @Column(name = "machine_id", length = 20)
    private String machineId;

    @Column(name = "part_number", length = 50)
    private String partNumber;

    @Column(name = "target_qty", precision = 10, scale = 2)
    private BigDecimal targetQty;

    @Column(name = "produced_qty", precision = 10, scale = 2)
    private BigDecimal producedQty;

    @Column(name = "current_daily_speed", precision = 10, scale = 2)
    private BigDecimal currentDailySpeed;
}