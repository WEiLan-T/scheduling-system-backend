package com.company.scheduling.repository;

import com.company.scheduling.domain.SystemUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface SystemUserRepo extends JpaRepository<SystemUser, Integer> {
    // 登录验证时，根据用户名精确查询用户档案
    Optional<SystemUser> findByUsername(String username);
}