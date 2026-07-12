package com.company.scheduling.repository;

import com.company.scheduling.domain.WeavingDailyLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WeavingDailyLogRepo extends JpaRepository<WeavingDailyLog, Integer> {
    // 用于记录每日织造车间的带坯产出数据和需求总量
}