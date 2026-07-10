package com.company.scheduling.repository;

import com.company.scheduling.domain.MachineActiveTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface MachineActiveTaskRepo extends JpaRepository<MachineActiveTask, Integer> {
    // 查找某台机器当前正在运行的任务
    List<MachineActiveTask> findByMachineId(String machineId);
}