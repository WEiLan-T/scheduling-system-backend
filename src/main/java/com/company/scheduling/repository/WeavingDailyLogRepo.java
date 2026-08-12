package com.company.scheduling.repository;

import com.company.scheduling.domain.WeavingDailyLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

@Repository
public interface WeavingDailyLogRepo extends JpaRepository<WeavingDailyLog, Long> {

    // 按唯一键组合查询（防重/覆盖更新）
    List<WeavingDailyLog> findByEntryYearAndEntryMonthAndEntryDayAndMachineNoAndShiftTypeAndTapeCodeAndModelSpec(
            Integer year, Integer month, Integer day, Integer machineNo, String shiftType, String tapeCode, String modelSpec);

    // 查询所有B级（需验证）记录
    @Query("SELECT w FROM WeavingDailyLog w WHERE w.dataQualityFlag = 'B'")
    List<WeavingDailyLog> findGradeBRecords();

    /** 按零件号集合定向查询（IN），供排产执行状态等场景替代全表 findAll */
    List<WeavingDailyLog> findByPartNumberIn(Collection<String> partNumbers);

    /**
     * 一次性加载已存在的唯一键集合（年-月-日-机台号-班次-带坯编号-型号规格），
     * 供大批量导入时内存判重，避免逐行DB查重
     */
    @Query("SELECT CONCAT(w.entryYear,'-',w.entryMonth,'-',w.entryDay,'-',w.machineNo,'-',w.shiftType,'-',w.tapeCode,'-',w.modelSpec) FROM WeavingDailyLog w")
    List<String> findAllExistingKeys();

    // 判断某机台号是否被台账引用（机台档案删除前引用校验）
    boolean existsByMachineNo(Integer machineNo);

    // 统计日期范围内的记录数
    @Query("SELECT COUNT(w) FROM WeavingDailyLog w WHERE w.entryDate BETWEEN :start AND :end")
    long countByDateRange(@Param("start") LocalDate start, @Param("end") LocalDate end);

    /**
     * 按带坯零件号聚合产量（SQL GROUP BY），用于日库存推算：
     * 累加账期日在 (from, to] 区间内的当班产量
     */
    @Query("SELECT w.partNumber, SUM(w.shiftOutput) FROM WeavingDailyLog w " +
            "WHERE w.entryDate > :from AND w.entryDate <= :to AND w.partNumber IS NOT NULL " +
            "GROUP BY w.partNumber")
    List<Object[]> sumOutputByPartNumber(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * 分页/搜索/筛选查询（供 list 端点可选分页使用）。
     * keyword 模糊匹配：零件号/带坯编号/型号规格；筛选列：机台号/班次/零件号。
     * 所有参数均可为 null（=不过滤）。
     * 注：运行库存在历史遗留 bytea 列，JPQL LOWER(bytea) 无对应函数会报 400，
     * 故改用 native 查询并对所有文本列 CAST(col AS text)，ILIKE 保持大小写不敏感语义；
     * 排序内联在 SQL 中（调用方传无排序 Pageable），countQuery 显式提供避免派生歧义。
     */
    @Query(value = "SELECT * FROM weaving_daily_log w WHERE " +
            "(:keyword IS NULL OR CAST(w.part_number AS text) ILIKE '%' || CAST(:keyword AS text) || '%' " +
            "OR CAST(w.tape_code AS text) ILIKE '%' || CAST(:keyword AS text) || '%' " +
            "OR CAST(w.model_spec AS text) ILIKE '%' || CAST(:keyword AS text) || '%') " +
            "AND (:machineNo IS NULL OR CAST(w.machine_no AS text) = CAST(:machineNo AS text)) " +
            "AND (:shiftType IS NULL OR CAST(w.shift_type AS text) = CAST(:shiftType AS text)) " +
            "AND (:partNumber IS NULL OR CAST(w.part_number AS text) = CAST(:partNumber AS text)) " +
            "ORDER BY w.entry_date DESC, w.id DESC",
           countQuery = "SELECT COUNT(*) FROM weaving_daily_log w WHERE " +
            "(:keyword IS NULL OR CAST(w.part_number AS text) ILIKE '%' || CAST(:keyword AS text) || '%' " +
            "OR CAST(w.tape_code AS text) ILIKE '%' || CAST(:keyword AS text) || '%' " +
            "OR CAST(w.model_spec AS text) ILIKE '%' || CAST(:keyword AS text) || '%') " +
            "AND (:machineNo IS NULL OR CAST(w.machine_no AS text) = CAST(:machineNo AS text)) " +
            "AND (:shiftType IS NULL OR CAST(w.shift_type AS text) = CAST(:shiftType AS text)) " +
            "AND (:partNumber IS NULL OR CAST(w.part_number AS text) = CAST(:partNumber AS text))",
           nativeQuery = true)
    Page<WeavingDailyLog> search(@Param("keyword") String keyword,
                                 @Param("machineNo") Integer machineNo,
                                 @Param("shiftType") String shiftType,
                                 @Param("partNumber") String partNumber,
                                 Pageable pageable);
}
