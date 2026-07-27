package com.company.scheduling.repository;

import com.company.scheduling.domain.CoexDailyLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface CoexDailyLogRepo extends JpaRepository<CoexDailyLog, Integer> {
    List<CoexDailyLog> findAllByOrderByEntryDateDesc();
    List<CoexDailyLog> findByFinishedPartNumber(String finishedPartNumber);

    // 🌟 新增：基于 账期+产线+订单+零件 的联合防重接口
    boolean existsByEntryDateAndLineIdAndOrderNumberAndFinishedPartNumber(LocalDate entryDate, String lineId, String orderNumber, String finishedPartNumber);
}