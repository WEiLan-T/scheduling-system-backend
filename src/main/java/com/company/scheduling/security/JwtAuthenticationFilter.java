package com.company.scheduling.security;

import com.company.scheduling.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    public JwtAuthenticationFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // 1. 按照国际规范，从 HTTP 请求头 (Header) 的 Authorization 字段中提取 Token
        String header = request.getHeader("Authorization");
        String token = null;

        if (header != null && header.startsWith("Bearer ")) {
            token = header.substring(7); // 剔除前缀 "Bearer "，剩下真正的 Token 字符串
        }

        // 2. 验证 Token 的合法性
        if (token != null && jwtUtil.validateToken(token)) {
            // 3. 如果合法，提取出用户名
            String username = jwtUtil.getUsernameFromToken(token);

            // 4. 告知 Spring Security：这个人身份没问题，放行！
            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        username, null, new ArrayList<>() // 这里后续可以加入 RBAC 角色权限控制
                );
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }

        // 继续执行下一个拦截器或业务代码
        filterChain.doFilter(request, response);
    }
}