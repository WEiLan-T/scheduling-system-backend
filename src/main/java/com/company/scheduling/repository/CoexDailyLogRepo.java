package com.company.scheduling.repository;

import com.company.scheduling.domain.CoexDailyLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface CoexDailyLogRepo extends JpaRepository<CoexDailyLog, Long> {

    // 按唯一键组合查询（防重/覆盖更新）
    List<CoexDailyLog> findByLogDateAndMachineNoAndProductModelAndColor(
            LocalDate logDate, String machineNo, String productModel, String color);

    // 查询所有B级（需验证）记录
    @Query("SELECT c FROM CoexDailyLog c WHERE c.dataQualityFlag = 'B'")
    List<CoexDailyLog> findGradeBRecords();

    /**
     * 按带坯零件号聚合共挤消耗（SQL GROUP BY），用于日库存推算：
     * 共挤台账无带坯零件号字段，通过工艺路线（产品型号→成品型号→带坯零件号）关联，
     * 累加账期日在 (from, to] 区间内的产能米数
     */
    @Query("SELECT p.tapePartNumber, SUM(c.capacityMeters) FROM CoexDailyLog c, ProductProcess p " +
            "WHERE c.productModel = p.finishedModelSpec " +
            "AND c.logDate > :from AND c.logDate <= :to " +
            "GROUP BY p.tapePartNumber")
    List<Object[]> sumConsumptionByTapePartNumber(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
