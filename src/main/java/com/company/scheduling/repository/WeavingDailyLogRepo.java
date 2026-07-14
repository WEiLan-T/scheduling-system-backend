package com.company.scheduling.repository;

import com.company.scheduling.domain.WeavingDailyLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WeavingDailyLogRepo extends JpaRepository<WeavingDailyLog, Integer> {
    Optional<WeavingDailyLog> findFirstByTapePartNumberOrderByEntryDateDesc(String tapePartNumber);

    // 🌟 新增：算法优先通过带坯编号查产能
    Optional<WeavingDailyLog> findFirstByTapeNumberOrderByEntryDateDesc(String tapeNumber);

    List<WeavingDailyLog> findAllByOrderByEntryDateDesc();
}