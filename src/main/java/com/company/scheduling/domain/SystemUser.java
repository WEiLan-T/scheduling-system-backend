package com.company.scheduling.domain;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "system_users")
public class SystemUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Integer userId;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    // 映射到数据库的 password_hash 字段
    @Column(name = "password_hash", nullable = false, length = 255)
    private String password;

    @Column(nullable = false, length = 20)
    private String role;
}