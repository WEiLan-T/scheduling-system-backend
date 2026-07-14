package com.company.scheduling.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;

@Data @Entity @Table(name = "virtual_warehouse")
public class VirtualWarehouse {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private String tapePartNumber;
    private String tapeNumber; // 🌟 新增：物理带坯批次/编号
    private String finishedPartNumber;
    private BigDecimal currentStockMeters;
    private LocalDate entryDate;
    private String enteredBy;
}