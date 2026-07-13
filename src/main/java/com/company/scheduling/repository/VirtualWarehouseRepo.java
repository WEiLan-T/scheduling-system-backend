package com.company.scheduling.repository;

import com.company.scheduling.domain.VirtualWarehouse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VirtualWarehouseRepo extends JpaRepository<VirtualWarehouse, Integer> {
    Optional<VirtualWarehouse> findByTapePartNumber(String tapePartNumber);

    // 🌟 新增：支持前端按带坯零件号进行模糊搜索
    List<VirtualWarehouse> findByTapePartNumberContainingIgnoreCase(String keyword);
}