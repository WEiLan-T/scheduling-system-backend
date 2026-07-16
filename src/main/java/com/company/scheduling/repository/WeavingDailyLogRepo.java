package com.company.scheduling.repository;

import com.company.scheduling.domain.WeavingDailyLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface WeavingDailyLogRepo extends JpaRepository<WeavingDailyLog, Integer> {
    List<WeavingDailyLog> findAllByOrderByEntryDateDesc();
    Optional<WeavingDailyLog> findFirstByTapePartNumberOrderByEntryDateDesc(String tapePartNumber);
    // 👇 新增：用于排产算法抓取该型号的所有历史记录算平均产能
    List<WeavingDailyLog> findByTapePartNumber(String tapePartNumber);
}