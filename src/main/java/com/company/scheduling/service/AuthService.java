package com.company.scheduling.service;

import com.company.scheduling.domain.LoginLog;
import com.company.scheduling.domain.SystemUser;
import com.company.scheduling.repository.LoginLogRepo;
import com.company.scheduling.repository.SystemUserRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /**
     * 开放注册允许的普通角色白名单：
     * 防止任何人直接注册 ROLE_ADMIN/ROLE_PLANNER 等高权限账号提权；
     * 高权限账号必须由管理员在数据库侧创建（或通过 DataInitializer 初始化）。
     */
    private static final Set<String> ALLOWED_SELF_REGISTER_ROLES = Set.of(
            "ROLE_ENTRY_CLERK", "ROLE_WEAVING_CLERK", "ROLE_COEX_CLERK");

    /**
     * 系统全部合法角色（管理员创建/修改账号时的角色白名单）
     */
    public static final Set<String> ALL_ROLES = Set.of(
            "ROLE_ADMIN", "ROLE_PLANNER", "ROLE_WEAVING_CLERK", "ROLE_COEX_CLERK", "ROLE_ENTRY_CLERK");

    private final SystemUserRepo userRepo;
    private final PasswordEncoder passwordEncoder;
    private final LoginLogRepo loginLogRepo;

    public AuthService(SystemUserRepo userRepo, PasswordEncoder passwordEncoder, LoginLogRepo loginLogRepo) {
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
        this.loginLogRepo = loginLogRepo;
    }

    public String registerUser(String username, String rawPassword, String role) {
        if (username == null || username.trim().isEmpty()) {
            return "注册失败：用户名不能为空！";
        }
        if (rawPassword == null || rawPassword.isEmpty()) {
            return "注册失败：密码不能为空！";
        }
        String safeUsername = username.trim();
        if (userRepo.findByUsername(safeUsername).isPresent()) {
            return "注册失败：用户名已存在！";
        }

        // 角色白名单校验：非法或高权限角色一律降级为最普通录单员，防止注册接口被用于提权
        String safeRole = (role != null && ALLOWED_SELF_REGISTER_ROLES.contains(role.trim()))
                ? role.trim() : "ROLE_ENTRY_CLERK";
        if (role != null && !safeRole.equals(role.trim())) {
            log.warn("注册接口收到越权角色请求 [{}]，已强制降级为 ROLE_ENTRY_CLERK（账号: {}）", role, safeUsername);
        }

        SystemUser user = new SystemUser();
        user.setUsername(safeUsername);
        // 密码必须进行单向哈希加密，绝不能明文存入数据库
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRole(safeRole);
        user.setEnteredBy("SYSTEM_REG");

        userRepo.save(user);
        return "账号注册成功！身份: " + user.getRole();
    }

    // ==================== 管理员：人员账号管理 ====================

    /**
     * 管理员创建账号：角色必须属于系统合法角色集，密码必填。
     */
    @Transactional
    public Map<String, Object> adminCreateUser(String username, String rawPassword, String role, String currentUser) {
        if (username == null || username.trim().isEmpty()) throw new RuntimeException("创建失败：账号名不能为空！");
        if (rawPassword == null || rawPassword.isEmpty()) throw new RuntimeException("创建失败：密码不能为空！");
        if (role == null || !ALL_ROLES.contains(role.trim())) {
            throw new RuntimeException("创建失败：角色非法！合法角色：" + String.join(" / ", new ArrayList<>(ALL_ROLES)));
        }
        String safeUsername = username.trim();
        if (userRepo.findByUsername(safeUsername).isPresent()) {
            throw new RuntimeException("创建失败：账号 [" + safeUsername + "] 已存在！");
        }

        SystemUser user = new SystemUser();
        user.setUsername(safeUsername);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRole(role.trim());
        user.setEnteredBy(currentUser);
        userRepo.save(user);
        log.warn("管理员 [{}] 创建了账号 [{}]，角色 [{}]", currentUser, safeUsername, role.trim());
        return buildUserView(user);
    }

    /**
     * 管理员修改账号：重置密码 / 调整角色，均至少传一项。
     */
    @Transactional
    public Map<String, Object> adminUpdateUser(Integer id, String rawPassword, String role, String currentUser) {
        if (id == null) throw new RuntimeException("修改失败：缺少账号ID！");
        SystemUser user = userRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("修改失败：找不到该账号！"));
        if (role != null && !role.trim().isEmpty()) {
            if (!ALL_ROLES.contains(role.trim())) {
                throw new RuntimeException("修改失败：角色非法！合法角色：" + String.join(" / ", new ArrayList<>(ALL_ROLES)));
            }
            user.setRole(role.trim());
        }
        if (rawPassword != null && !rawPassword.isEmpty()) {
            user.setPasswordHash(passwordEncoder.encode(rawPassword));
        }
        user.setEnteredBy(currentUser);
        userRepo.save(user);
        log.warn("管理员 [{}] 修改了账号 [{}] 的角色/密码", currentUser, user.getUsername());
        return buildUserView(user);
    }

    /**
     * 管理员删除账号：禁止删除自己（防止管理员把自己删掉后失去管理入口）。
     */
    @Transactional
    public String adminDeleteUser(Integer id, String currentUser) {
        if (id == null) throw new RuntimeException("删除失败：缺少账号ID！");
        SystemUser user = userRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("删除失败：找不到该账号！"));
        if (user.getUsername().equals(currentUser)) {
            throw new RuntimeException("删除失败：不能删除当前登录的管理员账号！");
        }
        userRepo.delete(user);
        log.warn("管理员 [{}] 删除了账号 [{}]", currentUser, user.getUsername());
        return "账号 [" + user.getUsername() + "] 已删除！";
    }

    /** 账号列表（脱敏：绝不返回密码哈希） */
    public List<Map<String, Object>> adminListUsers() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (SystemUser user : userRepo.findAll()) {
            list.add(buildUserView(user));
        }
        return list;
    }

    private Map<String, Object> buildUserView(SystemUser user) {
        Map<String, Object> view = new HashMap<>();
        view.put("id", user.getId());
        view.put("username", user.getUsername());
        view.put("role", user.getRole());
        view.put("enteredBy", user.getEnteredBy());
        view.put("createdAt", user.getCreatedAt() != null ? user.getCreatedAt().toString() : null);
        return view;
    }

    // ==================== 管理员：登录日志查询 ====================

    /** 分页查询登录日志（时间倒序，page 从 0 开始） */
    public Page<LoginLog> adminListLoginLogs(int page, int size) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        return loginLogRepo.findAll(PageRequest.of(p, s, Sort.by(Sort.Direction.DESC, "loginAt", "id")));
    }

    /** 记录一次登录尝试（成功/失败），供登录链路调用；失败不影响登录主流程 */
    public void recordLogin(String username, boolean success, String ipAddress, String message) {
        try {
            LoginLog logEntry = new LoginLog();
            logEntry.setUsername(username != null ? username : "未知账号");
            logEntry.setLoginAt(LocalDateTime.now());
            logEntry.setSuccess(success);
            logEntry.setIpAddress(ipAddress);
            logEntry.setMessage(message);
            loginLogRepo.save(logEntry);
        } catch (Exception e) {
            // 日志记录失败绝不影响登录主流程
            log.warn("登录日志写入失败: {}", e.getMessage());
        }
    }
}