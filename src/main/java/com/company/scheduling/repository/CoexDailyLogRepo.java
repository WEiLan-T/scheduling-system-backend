package com.company.scheduling.repository;

import com.company.scheduling.domain.CoexDailyLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CoexDailyLogRepo extends JpaRepository<CoexDailyLog, Integer> {
    // 用于记录每日共挤成品产出，以及自动扣减库存时所需的带坯消耗量
}