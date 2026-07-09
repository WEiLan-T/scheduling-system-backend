package com.company.scheduling.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data // Lombok 注解，自动生成 Getter/Setter
@Entity
@Table(name = "virtual_warehouse")
public class VirtualWarehouse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "warehouse_id")
    private Integer warehouseId;

    @Column(name = "tape_part_number", nullable = false, unique = true)
    private UUID tapePartNumber;

    @Column(name = "current_qty", nullable = false, precision = 15, scale = 2)
    private BigDecimal currentQty;

    @Column(name = "last_updated_time", insertable = false, updatable = false)
    private LocalDateTime lastUpdatedTime;
}