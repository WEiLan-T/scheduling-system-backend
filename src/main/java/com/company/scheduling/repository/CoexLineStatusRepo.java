package com.company.scheduling.repository;

import com.company.scheduling.domain.CoexLineStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CoexLineStatusRepo extends JpaRepository<CoexLineStatus, String> {
    // 主键为产线号 (String类型)，用于管理车间产线状态
}