package com.company.scheduling.controller;

import com.company.scheduling.dto.DailyLogRequest;
import com.company.scheduling.service.DataEntryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/workshops/integration") // 报告中规划的版本化 OpenAPI 路径
public class DataEntryController {

    private final DataEntryService dataEntryService;

    public DataEntryController(DataEntryService dataEntryService) {
        this.dataEntryService = dataEntryService;
    }

    @PostMapping("/daily-logs")
    public ResponseEntity<String> submitDailyLog(@RequestBody DailyLogRequest request) {

        // 假设当前登录的数据录入员账号为 "ENTRY_CLERK_01" (后期将结合 JWT 动态获取)
        String currentUser = "ENTRY_CLERK_01";

        // 调用 Service 层处理业务
        String result = dataEntryService.recordDailyProduction(request, currentUser);

        return ResponseEntity.ok(result);
    }
}