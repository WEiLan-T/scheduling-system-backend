package com.company.scheduling.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Data
@Entity
@Table(name = "daily_data_entry_log")
public class DailyDataEntryLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Integer logId;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Column(name = "machine_id", length = 50)
    private String machineId;

    @Column(name = "worker_id", nullable = false, length = 50)
    private String workerId;

    @Column(name = "input_type", nullable = false, length = 20)
    private String inputType;

    @Column(name = "qty", nullable = false, precision = 15, scale = 2)
    private BigDecimal qty;

    @Column(name = "entered_by", nullable = false, length = 50)
    private String enteredBy;

    @Column(name = "tape_part_number", nullable = false)
    private UUID tapePartNumber;
}