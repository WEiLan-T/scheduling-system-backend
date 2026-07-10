package com.company.scheduling.repository;

import com.company.scheduling.domain.MachineResource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface MachineResourceRepo extends JpaRepository<MachineResource, String> {
    // 寻找是否有共挤机台绑定了指定的织造机台
    Optional<MachineResource> findByLinkedMachineId(String linkedMachineId);
}