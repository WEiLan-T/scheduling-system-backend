package com.company.scheduling.repository;

import com.company.scheduling.domain.VirtualWarehouse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface VirtualWarehouseRepo extends JpaRepository<VirtualWarehouse, Integer> {

    // 查找库存时必须同时定位【型号 + 编号】，实现精准多退少补和合并
    Optional<VirtualWarehouse> findByTapePartNumberAndTapeNumber(String tapePartNumber, String tapeNumber);

    List<VirtualWarehouse> findByTapePartNumberContainingIgnoreCase(String keyword);

    List<VirtualWarehouse> findByFinishedPartNumber(String finishedPartNumber);

    // 👇 新增此方法：兜底查询时，如果存在多个批次，只取第一条以防止抛出 NonUniqueResultException
    Optional<VirtualWarehouse> findFirstByTapePartNumber(String tapePartNumber);
    //新增：通过带坯物理编号反查库存，用于共挤车间自动带出型号
    Optional<VirtualWarehouse> findFirstByTapeNumber(String tapeNumber);
}