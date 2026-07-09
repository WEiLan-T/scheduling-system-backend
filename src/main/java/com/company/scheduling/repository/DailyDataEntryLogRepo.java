package com.company.scheduling.repository;

import com.company.scheduling.domain.DailyDataEntryLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DailyDataEntryLogRepo extends JpaRepository<DailyDataEntryLog, Integer> {
    // 基础的增删改查已经由 JpaRepository 默认提供
}