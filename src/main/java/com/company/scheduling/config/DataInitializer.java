package com.company.scheduling.config;

import com.company.scheduling.domain.SystemUser;
import com.company.scheduling.repository.SystemUserRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 数据初始化器：应用启动时检查 system_users 表，
 * 若不存在任何管理员账号，则自动创建默认管理员 admin/admin123，
 * 避免因数据库被清空或首次部署导致无法登录的问题。
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private static final String DEFAULT_ADMIN_USERNAME = "admin";
    private static final String DEFAULT_ADMIN_PASSWORD = "admin123";

    private final SystemUserRepo userRepo;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(SystemUserRepo userRepo, PasswordEncoder passwordEncoder) {
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        try {
            if (userRepo.findByUsername(DEFAULT_ADMIN_USERNAME).isEmpty()) {
                SystemUser admin = new SystemUser();
                admin.setUsername(DEFAULT_ADMIN_USERNAME);
                admin.setPasswordHash(passwordEncoder.encode(DEFAULT_ADMIN_PASSWORD));
                admin.setRole("ROLE_ADMIN");
                admin.setEnteredBy("SYSTEM_INIT");
                userRepo.save(admin);
                log.warn("未检测到管理员账号，已自动创建默认管理员: {}（请尽快修改密码）", DEFAULT_ADMIN_USERNAME);
            }
        } catch (Exception e) {
            // 初始化失败不阻断应用启动，仅记录日志
            log.error("默认管理员账号初始化失败: {}", e.getMessage(), e);
        }
    }
}
