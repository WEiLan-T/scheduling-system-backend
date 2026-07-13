package com.company.scheduling.repository;

import com.company.scheduling.domain.EstimatedProductionSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

@Repository
public interface EstimatedProductionScheduleRepo extends JpaRepository<EstimatedProductionSchedule, Integer> {

    // 🌟 查询织造车间目前已经排到的最晚完工日期
    @Query("SELECT MAX(e.weavingEndDate) FROM EstimatedProductionSchedule e")
    LocalDate findMaxWeavingEndDate();

    // 🌟 查询共挤车间目前已经排到的最晚完工日期
    @Query("SELECT MAX(e.coexEndDate) FROM EstimatedProductionSchedule e")
    LocalDate findMaxCoexEndDate();
}