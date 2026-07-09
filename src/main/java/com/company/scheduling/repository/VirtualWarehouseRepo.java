package com.company.scheduling.repository;

import com.company.scheduling.domain.VirtualWarehouse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VirtualWarehouseRepo extends JpaRepository<VirtualWarehouse, Integer> {
    // Spring Boot 会根据方法名自动生成 SQL，查询特定带坯的库存
    Optional<VirtualWarehouse> findByTapePartNumber(UUID tapePartNumber);
}