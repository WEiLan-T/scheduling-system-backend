package com.company.scheduling.dto;

import lombok.Data;

@Data
public class AuthRequest {
    private String username;
    private String password;
    private String role; // 注册时指定角色，登录时可忽略
}