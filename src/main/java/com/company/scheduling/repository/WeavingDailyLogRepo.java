package com.company.scheduling.repository;

import com.company.scheduling.domain.WeavingDailyLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface WeavingDailyLogRepo extends JpaRepository<WeavingDailyLog, Integer> {
    List<WeavingDailyLog> findAllByOrderByEntryDateDesc();
    Optional<WeavingDailyLog> findFirstByTapePartNumberOrderByEntryDateDesc(String tapePartNumber);
    List<WeavingDailyLog> findByTapePartNumber(String tapePartNumber);

    // 🌟 新增：基于 账期+机台+零件号+班次 的联合防重接口
    boolean existsByEntryDateAndMachineIdAndTapePartNumberAndShift(LocalDate entryDate, String machineId, String tapePartNumber, String shift);
}