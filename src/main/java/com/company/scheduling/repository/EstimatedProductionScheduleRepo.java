package com.company.scheduling.repository;

import com.company.scheduling.domain.EstimatedProductionSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EstimatedProductionScheduleRepo extends JpaRepository<EstimatedProductionSchedule, Integer> {
    // 用于存储和查询排产算法大脑推演出来的各车间开始、结束日期以及订单完成总天数
}