package com.company.scheduling.dto;

import lombok.Data;

@Data
public class AuthRequest {
    private String username;
    private String password;
    private String role; // 仅在注册时可选，如 ROLE_ADMIN
}