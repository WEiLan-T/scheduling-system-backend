package com.company.scheduling.repository;

import com.company.scheduling.domain.SystemUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SystemUserRepo extends JpaRepository<SystemUser, Integer> {
    // Spring Data JPA 智能推导：根据用户名查找用户
    Optional<SystemUser> findByUsername(String username);
}