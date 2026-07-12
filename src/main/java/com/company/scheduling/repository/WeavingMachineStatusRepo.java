package com.company.scheduling.repository;

import com.company.scheduling.domain.WeavingMachineStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WeavingMachineStatusRepo extends JpaRepository<WeavingMachineStatus, String> {
    // 主键为机台号 (String类型)，用于判断机台是在产、空闲还是维修
}