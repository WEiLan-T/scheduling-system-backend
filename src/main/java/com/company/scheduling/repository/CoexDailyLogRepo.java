package com.company.scheduling.repository;

import com.company.scheduling.domain.CoexDailyLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface CoexDailyLogRepo extends JpaRepository<CoexDailyLog, Integer> {
    List<CoexDailyLog> findAllByOrderByEntryDateDesc();
    // 👇 新增：用于排产算法抓取该型号的所有历史记录算平均产能
    List<CoexDailyLog> findByFinishedPartNumber(String finishedPartNumber);
}