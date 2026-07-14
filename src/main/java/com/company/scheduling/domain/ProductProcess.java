package com.company.scheduling.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "product_process")
public class ProductProcess {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(unique = true, nullable = false)
    private String finishedPartNumber; // 成品零件号 (主键/索引)

    @Column(nullable = false)
    private String tapePartNumber;     // 对应的带坯零件号

    private String warpSpec;           // 带坯经线型号 (工艺要求)
    private String weftSpec;           // 带坯纬线型号 (工艺要求)

    private String enteredBy;
    private LocalDateTime createdAt = LocalDateTime.now();
}