package com.company.scheduling.service;

import com.company.scheduling.domain.DailyDataEntryLog;
import com.company.scheduling.dto.DailyLogRequest;
import com.company.scheduling.repository.DailyDataEntryLogRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
public class DataEntryService {

    private final DailyDataEntryLogRepo logRepo;

    // Spring Boot 推荐的构造器注入方式
    public DataEntryService(DailyDataEntryLogRepo logRepo) {
        this.logRepo = logRepo;
    }

    /**
     * 处理手工录入的台账数据
     */
    @Transactional // 开启事务，保证数据一致性
    public String recordDailyProduction(DailyLogRequest request, String currentUser) {
        // 1. 将 DTO 转换为数据库实体 Entity
        DailyDataEntryLog log = new DailyDataEntryLog();
        log.setEntryDate(LocalDate.now()); // 记录当天日期
        log.setWorkerId(request.getWorkerId());
        log.setMachineId(request.getMachineId());
        log.setInputType(request.getInputType());
        log.setQty(request.getQty());
        log.setTapePartNumber(request.getTapePartNumber());

        // currentUser 代表当前登录系统的录入员（等做完权限认证后从 JWT 里取，现在先模拟传入）
        log.setEnteredBy(currentUser);

        // 2. 保存到数据库
        // 【注意】这里一旦 save 成功，就会触发我们在 PostgreSQL 里写的 trigger_update_virtual_warehouse 触发器！
        // 虚拟仓库的库存会自动加减，不需要我们在 Java 里手动写计算逻辑，完美发挥数据库性能。
        logRepo.save(log);

        return "数据台账录入成功，虚拟库存已自动更新！";
    }
}