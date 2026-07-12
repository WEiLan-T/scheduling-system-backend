package com.company.scheduling.service;

import com.company.scheduling.domain.SystemUser;
import com.company.scheduling.repository.SystemUserRepo;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final SystemUserRepo userRepo;
    private final PasswordEncoder passwordEncoder;

    public AuthService(SystemUserRepo userRepo, PasswordEncoder passwordEncoder) {
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
    }

    public String registerUser(String username, String rawPassword, String role) {
        if (userRepo.findByUsername(username).isPresent()) {
            return "注册失败：用户名已存在！";
        }

        SystemUser user = new SystemUser();
        user.setUsername(username);
        // 密码必须进行单向哈希加密，绝不能明文存入数据库
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRole(role != null ? role : "ROLE_ENTRY_CLERK");
        user.setEnteredBy("SYSTEM_REG");

        userRepo.save(user);
        return "账号注册成功！身份: " + user.getRole();
    }
}