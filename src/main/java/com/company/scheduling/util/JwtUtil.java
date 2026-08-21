package com.company.scheduling.util;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);

    // 签名密钥：从配置外部化读取（生产环境请通过环境变量 JWT_SECRET 覆盖，至少32字节）
    @Value("${jwt.secret:ThisIsASuperSecretKeyForSchedulingSystem2026}")
    private String secretString;

    private SecretKey key;

    @PostConstruct
    public void initKey() {
        byte[] bytes = secretString.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT 密钥(jwt.secret)长度必须至少32字节，当前仅 " + bytes.length + " 字节");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    // Token 有效期设置 (例如：8小时)
    private static final long EXPIRATION_TIME = 1000 * 60 * 60 * 8;

    /**
     * 生成 JWT Token
     * @param username 用户名
     * @param role 用户角色
     * @return 签发好的 JWT 字符串
     */
    public String generateToken(String username, String role) {
        return Jwts.builder()
                .subject(username) // 将用户名作为主体存放
                .claim("role", role) // 额外放入角色信息，方便后续进行权限控制 (RBAC)
                .issuedAt(new Date()) // 签发时间
                .expiration(new Date(System.currentTimeMillis() + EXPIRATION_TIME)) // 过期时间
                .signWith(key) // 使用我们的密钥进行数字签名，防篡改
                .compact();
    }

    /**
     * 验证 Token 是否合法且未过期
     */
    public boolean validateToken(String token) {
        try {
            // 如果能正常解析且签名匹配，说明是合法的
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            // 签名被篡改、Token 过期或格式错误，都会走到这里
            log.debug("无效的 JWT Token: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 从 Token 中提取用户名
     */
    public String getUsernameFromToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }
    /**
     * 从 Token 中提取角色 (Role)
     */
    public String getRoleFromToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .get("role", String.class);
    }
}
