package com.company.scheduling.config;

import com.company.scheduling.domain.SystemUser;
import com.company.scheduling.repository.SystemUserRepo;
import com.company.scheduling.security.JwtAuthenticationFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final SystemUserRepo userRepo; // 🌟 引入数据库访问层

    public SecurityConfig(JwtAuthenticationFilter jwtAuthFilter, SystemUserRepo userRepo) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.userRepo = userRepo;
    }

    // ================== 核心安全优化 1：实现数据库用户读取引擎 ==================
    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            // 1. 从我们的 system_users 表中查找该账号
            SystemUser user = userRepo.findByUsername(username)
                    .orElseThrow(() -> new UsernameNotFoundException("安全拦截：查无此账号 -> " + username));

            // 2. 将我们的实体转化为 Spring Security 认识的安保对象
            return User.builder()
                    .username(user.getUsername())
                    .password(user.getPasswordHash()) // 读取 Argon2 密文
                    .authorities(user.getRole())      // 绑定角色的权限
                    .build();
        };
    }

    // ================== 核心安全优化 2：装配高强度密码比对器 ==================
    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(userDetailsService()); // 指定去哪里查人
        authProvider.setPasswordEncoder(passwordEncoder());       // 指定怎么比对密码
        return authProvider;
    }

    // 暴露核心认证管理器供 AuthController 调用
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    // ================== 核心安全优化 3：零信任 URL 访问策略 ==================
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // 前后端分离采用 JWT，天然防范 CSRF
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // 🌟 将上面配置好的密码比对引擎装载进过滤链 (打破 StackOverflow 死循环的关键)
                .authenticationProvider(authenticationProvider())

                .authorizeHttpRequests(auth -> auth
                        // 放行内部路由与错误页面
                        .dispatcherTypeMatchers(DispatcherType.FORWARD, DispatcherType.ERROR).permitAll()
                        // 放行前端静态资源
                        .requestMatchers("/", "/index.html", "/app.js", "/utils.js", "/css/**", "/js/**", "/images/**", "/favicon.ico", "/error").permitAll()
                        // 放行登录与注册
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        // 其他一律零信任拦截
                        .anyRequest().authenticated()
                )
                // 挂载自定义的 JWT 令牌核验过滤器
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    // ================== 核心安全优化 4：Argon2 军工级哈希算法 ==================
    @Bean
    public PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }
}