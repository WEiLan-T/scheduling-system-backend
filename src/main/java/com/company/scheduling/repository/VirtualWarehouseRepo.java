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

    // 按带坯零件号查询全部快照记录（供排产/询单整根贪心消耗，内存中做快照去重与FIFO排序）
    List<VirtualWarehouse> findByPartNumber(String partNumber);

    // 查询最新一期快照的全部库存记录
    @Query("SELECT v FROM VirtualWarehouse v WHERE v.snapshotDate = (SELECT MAX(v2.snapshotDate) FROM VirtualWarehouse v2)")
    List<VirtualWarehouse> findLatestSnapshot();

    // 查询指定快照日期的全部库存记录
    List<VirtualWarehouse> findBySnapshotDate(LocalDate snapshotDate);

    // 查询某(零件号+带坯编号)的历史快照记录（按快照日期倒序）
    List<VirtualWarehouse> findByPartNumberAndTapeCodeOrderBySnapshotDateDesc(String partNumber, String tapeCode);

    // 查询指定快照日期中已落库（机台为空）的全部库存记录
    List<VirtualWarehouse> findBySnapshotDateAndMachineNoIsNull(LocalDate snapshotDate);

    /**
     * 一次性加载已存在的唯一键集合（零件号|带坯编号|快照日期），
     * 供大批量导入时内存判重，避免逐行DB查重
     */
    @Query("SELECT CONCAT(v.partNumber,'|',v.tapeCode,'|',v.snapshotDate) FROM VirtualWarehouse v")
    List<String> findAllExistingKeys();

    // 批量加载指定日期之前的历史快照值（零件号|带坯编号|快照日期|库存），供批量推算上一期值
    @Query("SELECT v.partNumber, v.tapeCode, v.snapshotDate, v.stockMeters FROM VirtualWarehouse v WHERE v.snapshotDate < :date")
    List<Object[]> findStockBefore(@Param("date") LocalDate date);

    // 查询不超过指定日期的最新快照日期（日库存推算的月度锚点）
    @Query("SELECT MAX(v.snapshotDate) FROM VirtualWarehouse v WHERE v.snapshotDate <= :date")
    LocalDate findLatestSnapshotDateOnOrBefore(@Param("date") LocalDate date);

    // ================ 兼容方法（供排产引擎调用，映射到新字段） ================

    /**
     * 兼容旧接口：按成品零件号查询库存（旧方法名保留，查询映射到新字段partNumber）
     * 返回最新一期快照中匹配的记录，避免跨快照重复累计
     */
    @Query("SELECT v FROM VirtualWarehouse v WHERE v.partNumber = :partNumber " +
            "AND v.snapshotDate = (SELECT MAX(v2.snapshotDate) FROM VirtualWarehouse v2)")
    List<VirtualWarehouse> findByFinishedPartNumber(@Param("partNumber") String partNumber);
}
