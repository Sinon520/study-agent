package com.studyagent.controller;

import com.studyagent.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查（第 1 周唯一允许存在的 Controller）。
 */
@Tag(name = "健康检查", description = "服务存活探测")
@RestController
public class HealthController {

    @Operation(summary = "健康检查", description = "返回服务状态、服务名与服务器当前时间")
    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("service", "server-java");
        data.put("time", LocalDateTime.now().toString());
        return Result.ok(data);
    }
}
