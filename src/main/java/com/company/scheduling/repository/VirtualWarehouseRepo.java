package com.company.scheduling.repository;

import com.company.scheduling.domain.VirtualWarehouse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface VirtualWarehouseRepo extends JpaRepository<VirtualWarehouse, Integer> {

    /**
     * 🌟 核心自定义查询方法
     * 织造生产时、共挤扣减时、或者人工盘点时，都需要通过“带坯零件号”来快速定位并更新这笔带坯的已有库存米数。
     */
    Optional<VirtualWarehouse> findByTapePartNumber(String tapePartNumber);
}