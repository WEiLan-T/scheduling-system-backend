package com.company.scheduling.repository;

import com.company.scheduling.domain.VirtualWarehouse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface VirtualWarehouseRepo extends JpaRepository<VirtualWarehouse, Long> {

    // 按唯一键组合查询（零件号+带坯编号+快照日期）
    List<VirtualWarehouse> findByPartNumberAndTapeCodeAndSnapshotDate(String partNumber, String tapeCode, LocalDate snapshotDate);

    // 查询最新一期快照的全部库存记录
    @Query("SELECT v FROM VirtualWarehouse v WHERE v.snapshotDate = (SELECT MAX(v2.snapshotDate) FROM VirtualWarehouse v2)")
    List<VirtualWarehouse> findLatestSnapshot();

    // 查询指定快照日期的全部库存记录
    List<VirtualWarehouse> findBySnapshotDate(LocalDate snapshotDate);

    // 查询某(零件号+带坯编号)的历史快照记录（按快照日期倒序）
    List<VirtualWarehouse> findByPartNumberAndTapeCodeOrderBySnapshotDateDesc(String partNumber, String tapeCode);

    // ================ 兼容方法（供排产引擎调用，映射到新字段） ================

    /**
     * 兼容旧接口：按成品零件号查询库存（旧方法名保留，查询映射到新字段partNumber）
     * 返回最新一期快照中匹配的记录，避免跨快照重复累计
     */
    @Query("SELECT v FROM VirtualWarehouse v WHERE v.partNumber = :partNumber " +
            "AND v.snapshotDate = (SELECT MAX(v2.snapshotDate) FROM VirtualWarehouse v2)")
    List<VirtualWarehouse> findByFinishedPartNumber(@Param("partNumber") String partNumber);
}
