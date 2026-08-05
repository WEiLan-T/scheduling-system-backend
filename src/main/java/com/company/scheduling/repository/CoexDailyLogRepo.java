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

    // ================ 兼容方法（供排产引擎调用，映射到新字段） ================

    // 兼容旧接口：按成品零件号查询台账（旧方法名保留，查询映射到新字段productModel）
    @Query("SELECT c FROM CoexDailyLog c WHERE c.productModel = :finishedPartNumber")
    List<CoexDailyLog> findByFinishedPartNumber(@Param("finishedPartNumber") String finishedPartNumber);

    /**
     * 兼容旧接口：按成品型号(productModel)分组统计平均产能(capacityMeters)
     * 返回行: [成品型号, 平均产能]
     */
    @Query("SELECT c.productModel, AVG(c.capacityMeters) FROM CoexDailyLog c " +
            "WHERE c.capacityMeters IS NOT NULL AND c.capacityMeters > 0 GROUP BY c.productModel")
    List<Object[]> findAvgCapacityGroupByFinishedPartNumber();
}
