package com.company.scheduling.repository;

import com.company.scheduling.domain.EstimatedProductionSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface EstimatedProductionScheduleRepo extends JpaRepository<EstimatedProductionSchedule, Integer> {

    // 🌟 查询织造车间目前已经排到的最晚完工日期
    @Query("SELECT MAX(e.weavingEndDate) FROM EstimatedProductionSchedule e")
    LocalDate findMaxWeavingEndDate();

    // 🌟 查询共挤车间目前已经排到的最晚完工日期
    @Query("SELECT MAX(e.coexEndDate) FROM EstimatedProductionSchedule e")
    LocalDate findMaxCoexEndDate();

    List<EstimatedProductionSchedule> findByWeavingMachineIdAndWeavingEndDateAfter(String machineId, LocalDateTime after);

    List<EstimatedProductionSchedule> findByCoexLineIdAndCoexEndDateAfter(String lineId, LocalDateTime after);

    // 🌟 新增：批量查询所有未来织造完工记录
    List<EstimatedProductionSchedule> findByWeavingEndDateAfter(LocalDateTime after);

    // 🌟 新增：批量查询所有未来共挤完工记录
    List<EstimatedProductionSchedule> findByCoexEndDateAfter(LocalDateTime after);

    List<EstimatedProductionSchedule> findByOrderId(String orderId);

    @Query("SELECT e.orderId, MIN(CASE WHEN e.weavingStartDate IS NOT NULL THEN e.weavingStartDate ELSE e.coexStartDate END), MAX(e.coexEndDate) FROM EstimatedProductionSchedule e GROUP BY e.orderId")
    List<Object[]> findScheduleSummaryByOrder();
}