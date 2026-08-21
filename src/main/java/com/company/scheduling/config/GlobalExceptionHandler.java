package com.company.scheduling.config;

import org.apache.poi.ooxml.POIXMLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MultipartException;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理器：确保所有后端异常以统一 JSON 格式返回给前端，
 * 前端可通过 error.response.data.message 精准读取错误原因。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务逻辑异常（排产引擎主动抛出的 RuntimeException）
     * 返回 400 Bad Request + 明确错误消息
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(RuntimeException ex) {
        log.warn("业务异常: {}", ex.getMessage());
        Map<String, Object> body = new HashMap<>();
        body.put("status", 400);
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * 登录认证失败（账号不存在或密码错误）
     * 返回 401 Unauthorized + 明确提示，前端据此提示“帐号或密码错误”
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleBadCredentials(BadCredentialsException ex) {
        log.warn("登录认证失败: {}", ex.getMessage());
        Map<String, Object> body = new HashMap<>();
        body.put("status", 401);
        body.put("message", "账号或密码错误！");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    /**
     * 权限不足异常
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("status", 403);
        body.put("message", "权限不足，无法执行此操作！");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    /**
     * Excel文件解析异常
     */
    @ExceptionHandler(POIXMLException.class)
    public ResponseEntity<Map<String, String>> handleExcelException(POIXMLException ex) {
        log.warn("Excel解析异常: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", "Excel文件解析失败: " + ex.getMessage()));
    }

    /**
     * MultipartFile上传异常（文件为空/格式非法）
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, String>> handleMultipartException(MultipartException ex) {
        log.warn("文件上传异常: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", "请上传有效的Excel文件"));
    }

    /**
     * 数据质量/参数校验异常
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("参数校验异常: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    /**
     * 空指针异常专项处理：排产引擎中空值问题定位
     */
    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<Map<String, Object>> handleNullPointerException(NullPointerException ex) {
        log.error("空指针异常（可能为排产引擎数据不完整）", ex);
        Map<String, Object> body = new HashMap<>();
        body.put("status", 500);
        body.put("message", "数据完整性异常：排产引擎检测到空值，请检查工艺库和产能数据是否完整。详情：" + ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
    
    /**
     * 兜底：所有未预见的异常
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        log.error("系统内部错误", ex);
        Map<String, Object> body = new HashMap<>();
        body.put("status", 500);
        body.put("message", "系统内部错误：" + (ex.getMessage() != null ? ex.getMessage() : "未知异常，请联系管理员"));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
