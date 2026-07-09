package com.company.scheduling.service;

import com.company.scheduling.domain.SystemUser;
import com.company.scheduling.dto.AuthRequest;
import com.company.scheduling.repository.SystemUserRepo;
import com.company.scheduling.util.JwtUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class AuthService {

    private final SystemUserRepo userRepo;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil; // 1. 声明 JwtUtil

    // 2. 将 JwtUtil 加入到构造器中进行注入
    public AuthService(SystemUserRepo userRepo, PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    public String register(AuthRequest request) {
        if (userRepo.findByUsername(request.getUsername()).isPresent()) {
            return "注册失败：用户名已存在！";
        }

        SystemUser user = new SystemUser();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole(request.getRole() != null ? request.getRole() : "ROLE_ENTRY_CLERK");

        userRepo.save(user);
        return "注册成功！欢迎加入排产系统，您的角色是：" + user.getRole();
    }

    public String login(AuthRequest request) {
        Optional<SystemUser> userOpt = userRepo.findByUsername(request.getUsername());

        if (userOpt.isPresent()) {
            SystemUser user = userOpt.get();
            if (passwordEncoder.matches(request.getPassword(), user.getPassword())) {

                // 3. 核心：密码校验通过后，调用工具类生成包含用户名和角色的 Token
                String token = jwtUtil.generateToken(user.getUsername(), user.getRole());

                return "登录成功！\n" +
                        "您的专属通行证 (JWT Token) 是：\n" +
                        token;
            }
        }
        return "登录失败：用户名或密码错误！";
    }
}