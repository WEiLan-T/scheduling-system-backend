package com.company.scheduling.repository;

import com.company.scheduling.domain.VirtualWarehouse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /**
     * 分页/搜索/筛选查询（供 list 端点可选分页使用），与旧全量接口一致仅查最新一期快照。
     * keyword 模糊匹配：零件号/带坯编号/型号规格；筛选列：零件号/机台/库存类型。
     * 所有参数均可为 null（=不过滤）。
     * 注：运行库存在历史遗留 bytea 列，JPQL LOWER(bytea) 无对应函数会报 400，
     * 故改用 native 查询并对所有文本列 CAST(col AS text)，ILIKE 保持大小写不敏感语义；
     * part_number 排序列同样 cast 为 text；排序内联在 SQL 中（调用方传无排序 Pageable），
     * countQuery 显式提供（含子查询，避免 count 派生歧义）。
     */
    @Query(value = "SELECT * FROM virtual_warehouse v WHERE v.snapshot_date = (SELECT MAX(v2.snapshot_date) FROM virtual_warehouse v2) " +
            "AND (:keyword IS NULL OR CAST(v.part_number AS text) ILIKE '%' || CAST(:keyword AS text) || '%' " +
            "OR CAST(v.tape_code AS text) ILIKE '%' || CAST(:keyword AS text) || '%' " +
            "OR CAST(v.model_spec AS text) ILIKE '%' || CAST(:keyword AS text) || '%') " +
            "AND (:partNumber IS NULL OR CAST(v.part_number AS text) = CAST(:partNumber AS text)) " +
            "AND (:machineNo IS NULL OR CAST(v.machine_no AS text) = CAST(:machineNo AS text)) " +
            "AND (:stockType IS NULL OR CAST(v.stock_type AS text) = CAST(:stockType AS text)) " +
            "ORDER BY CAST(v.part_number AS text) ASC, v.id ASC",
           countQuery = "SELECT COUNT(*) FROM virtual_warehouse v WHERE v.snapshot_date = (SELECT MAX(v2.snapshot_date) FROM virtual_warehouse v2) " +
            "AND (:keyword IS NULL OR CAST(v.part_number AS text) ILIKE '%' || CAST(:keyword AS text) || '%' " +
            "OR CAST(v.tape_code AS text) ILIKE '%' || CAST(:keyword AS text) || '%' " +
            "OR CAST(v.model_spec AS text) ILIKE '%' || CAST(:keyword AS text) || '%') " +
            "AND (:partNumber IS NULL OR CAST(v.part_number AS text) = CAST(:partNumber AS text)) " +
            "AND (:machineNo IS NULL OR CAST(v.machine_no AS text) = CAST(:machineNo AS text)) " +
            "AND (:stockType IS NULL OR CAST(v.stock_type AS text) = CAST(:stockType AS text))",
           nativeQuery = true)
    Page<VirtualWarehouse> searchLatestSnapshot(@Param("keyword") String keyword,
                                                @Param("partNumber") String partNumber,
                                                @Param("machineNo") String machineNo,
                                                @Param("stockType") String stockType,
                                                Pageable pageable);
}
