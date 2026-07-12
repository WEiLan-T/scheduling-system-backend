package com.company.scheduling.repository;

import com.company.scheduling.domain.MasterProductionPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MasterProductionPlanRepo extends JpaRepository<MasterProductionPlan, String> {
    // 主键为计划编号 (String类型)，用于串联关联订单、织造机台和共挤产线
}