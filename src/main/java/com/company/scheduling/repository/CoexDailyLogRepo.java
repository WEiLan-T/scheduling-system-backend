package com.company.scheduling.repository;

import com.company.scheduling.domain.CoexDailyLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List; // 确保导入 List

@Repository
public interface CoexDailyLogRepo extends JpaRepository<CoexDailyLog, Integer> {
    // 🌟 新增：获取所有历史台账，并按日期倒序排列
    List<CoexDailyLog> findAllByOrderByEntryDateDesc();
}