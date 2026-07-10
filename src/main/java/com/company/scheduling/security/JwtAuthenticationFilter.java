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
import org.springframework.security.core.authority.SimpleGrantedAuthority; // 新增导入
import org.springframework.security.core.GrantedAuthority; // 新增导入
import java.util.List; // 新增导入

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
            // 3. 提取出用户名和角色
            String username = jwtUtil.getUsernameFromToken(token);
            String role = jwtUtil.getRoleFromToken(token); // 🌟 新增：提取角色

            // 4. 告知 Spring Security：这个人身份没问题，并且赋予他对应的角色权限
            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {

                // 🌟 新增：将字符串角色转换为 Spring 认识的权限对象
                List<GrantedAuthority> authorities = new ArrayList<>();
                authorities.add(new SimpleGrantedAuthority(role));

                // 把 authorities 传进去，替代之前写的 new ArrayList<>()
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        username, null, authorities
                );
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }

        // 继续执行下一个拦截器或业务代码
        filterChain.doFilter(request, response);
    }
}