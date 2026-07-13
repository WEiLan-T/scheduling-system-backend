package com.company.scheduling.repository;

import com.company.scheduling.domain.WeavingDailyLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List; // 确保导入 List
import java.util.Optional;

@Repository
public interface WeavingDailyLogRepo extends JpaRepository<WeavingDailyLog, Integer> {
    Optional<WeavingDailyLog> findFirstByTapePartNumberOrderByEntryDateDesc(String tapePartNumber);

    // 🌟 新增：获取所有历史台账，并按日期倒序排列
    List<WeavingDailyLog> findAllByOrderByEntryDateDesc();
}