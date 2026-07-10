package com.company.scheduling.controller;

import com.company.scheduling.dto.DailyLogRequest;
import com.company.scheduling.service.DataEntryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

// 引入 Java 原生的防伪身份接口
import java.security.Principal;

@RestController
@RequestMapping("/api/v1/workshops/integration")
public class DataEntryController {

    private final DataEntryService dataEntryService;

    public DataEntryController(DataEntryService dataEntryService) {
        this.dataEntryService = dataEntryService;
    }

    @PostMapping("/daily-logs")
    @PreAuthorize("hasAuthority('ROLE_ENTRY_CLERK')") // 🌟 仅限录入员访问
    public ResponseEntity<String> submitDailyLog(@RequestBody DailyLogRequest request, Principal principal) {

        // 1. 从 Spring Security 的上下文中自动提取当前发起请求的真实用户名
        // 只要能走到这一行，说明 JWT 令牌绝对是合法的，这里的名字绝对不可能造假。
        String currentUser = principal.getName();

        // 2. 将这个真实的用户名传递给大脑 (Service 层)
        String result = dataEntryService.recordDailyProduction(request, currentUser);

        return ResponseEntity.ok(result + " 操作已留痕，记录人：" + currentUser);
    }
}