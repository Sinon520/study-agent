package com.studyagent.controller;

import com.studyagent.common.Result;
import com.studyagent.model.vo.ProgressVO;
import com.studyagent.security.UserContext;
import com.studyagent.service.ProgressService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 进度统计接口。
 */
@RestController
@RequestMapping("/progress")
@RequiredArgsConstructor
@Tag(name = "05-进度统计", description = "学习时长、完成率、科目分布、错题掌握度")
@SecurityRequirement(name = "bearerAuth")
public class ProgressController {

    private final ProgressService progressService;

    @GetMapping("/dashboard")
    @Operation(summary = "进度页完整数据",
            description = "一次返回概览卡片 + 4 张图的数据（每日时长折线、完成率折线、科目饼图、掌握度柱状）")
    public Result<ProgressVO.ProgressDashboard> dashboard(
            @Parameter(description = "开始日期，默认 30 天前") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "结束日期，默认今天") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        LocalDate end = endDate != null ? endDate : LocalDate.now();
        LocalDate start = startDate != null ? startDate : end.minusDays(29);
        return Result.ok(progressService.dashboard(UserContext.userId(), start, end));
    }

    @GetMapping("/recent")
    @Operation(summary = "近 N 天进度", description = "days 取值 1~365，默认 30；小程序进度页用")
    public Result<ProgressVO.ProgressDashboard> recent(
            @Parameter(description = "天数") @RequestParam(defaultValue = "30") Integer days) {
        return Result.ok(progressService.recent(UserContext.userId(), days));
    }

    @GetMapping("/overview")
    @Operation(summary = "进度总览卡片", description = "只要概览数字时用这个，比 dashboard 轻")
    public Result<ProgressVO.Overview> overview(
            @Parameter(description = "天数") @RequestParam(defaultValue = "30") Integer days) {
        return Result.ok(progressService.recent(UserContext.userId(), days).getOverview());
    }
}
