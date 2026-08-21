package com.company.scheduling.controller;

import com.company.scheduling.dto.AuthRequest;
import com.company.scheduling.service.AuthService;
import com.company.scheduling.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final AuthService authService;

    public AuthController(AuthenticationManager authenticationManager, JwtUtil jwtUtil, AuthService authService) {
        this.authenticationManager = authenticationManager;
        this.jwtUtil = jwtUtil;
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<String> register(@RequestBody AuthRequest request) {
        String result = authService.registerUser(request.getUsername(), request.getPassword(), request.getRole());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/login")
    public ResponseEntity<String> login(@RequestBody AuthRequest request, HttpServletRequest httpRequest) {
        String clientIp = extractClientIp(httpRequest);
        try {
            // 1. 让 Spring Security 去核对账号密码
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
            );

            // 2. 提取数据库中真实的角色
            String role = authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .findFirst()
                    .orElse("ROLE_ENTRY_CLERK");

            // 3. 签发 JWT 令牌
            String token = jwtUtil.generateToken(request.getUsername(), role);

            // 4. 记录登录成功日志（供管理员审计；记录失败不影响登录）
            authService.recordLogin(request.getUsername(), true, clientIp, "登录成功");
            return ResponseEntity.ok(token);
        } catch (BadCredentialsException e) {
            // 登录失败也留痕：账号不存在与密码错误均归为凭据错误
            authService.recordLogin(request.getUsername(), false, clientIp, "账号或密码错误");
            throw e;
        }
    }

    /** 提取客户端 IP（兼容 X-Forwarded-For 反向代理场景） */
    private String extractClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}