package com.company.scheduling.config;

import com.company.scheduling.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity; // 导入开关

@Configuration
@EnableWebSecurity
@EnableMethodSecurity // 🌟 开启全局方法级权限控制
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;

    // 注入我们刚才写的门卫
    public SecurityConfig(JwtAuthenticationFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                // 声明：我们使用 JWT，不需要传统的 HTTP Session
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // 重新制定访问规则
                .authorizeHttpRequests(auth -> auth
                        // 1. 登录和注册接口，所有人都可以访问 (大门敞开)
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        // 2. 剩下的所有接口 (包括录入台账、估算完工)，必须有合法 Token 才能进！(全线封锁)
                        .anyRequest().authenticated()
                )
                // 把我们的 JWT 门卫，安插在 Spring Security 默认的用户密码门卫之前
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }
}