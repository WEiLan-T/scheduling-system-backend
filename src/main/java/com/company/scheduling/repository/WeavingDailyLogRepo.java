package com.company.scheduling.repository;

import com.company.scheduling.domain.WeavingDailyLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WeavingDailyLogRepo extends JpaRepository<WeavingDailyLog, Integer> {
    // 🌟 新增：获取某带坯最近一次的生产台账，用于排产大脑提取真实日产能
    Optional<WeavingDailyLog> findFirstByTapePartNumberOrderByEntryDateDesc(String tapePartNumber);
}