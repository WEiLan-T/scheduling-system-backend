package com.company.scheduling.repository;

import com.company.scheduling.domain.WeavingDailyLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface WeavingDailyLogRepo extends JpaRepository<WeavingDailyLog, Long> {

    // 按唯一键组合查询（防重/覆盖更新）
    List<WeavingDailyLog> findByEntryYearAndEntryMonthAndEntryDayAndMachineNoAndShiftTypeAndTapeCodeAndModelSpec(
            Integer year, Integer month, Integer day, Integer machineNo, String shiftType, String tapeCode, String modelSpec);

    // 查询所有B级（需验证）记录
    @Query("SELECT w FROM WeavingDailyLog w WHERE w.dataQualityFlag = 'B'")
    List<WeavingDailyLog> findGradeBRecords();

    /**
     * 一次性加载已存在的唯一键集合（年-月-日-机台号-班次-带坯编号-型号规格），
     * 供大批量导入时内存判重，避免逐行DB查重
     */
    @Query("SELECT CONCAT(w.entryYear,'-',w.entryMonth,'-',w.entryDay,'-',w.machineNo,'-',w.shiftType,'-',w.tapeCode,'-',w.modelSpec) FROM WeavingDailyLog w")
    List<String> findAllExistingKeys();

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
}
