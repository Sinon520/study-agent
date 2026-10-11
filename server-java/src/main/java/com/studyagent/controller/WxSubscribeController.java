package com.studyagent.controller;

import com.studyagent.common.Result;
import com.studyagent.config.WxProperties;
import com.studyagent.model.dto.WxSubscribeRequest;
import com.studyagent.model.vo.WxPushLogVO;
import com.studyagent.model.vo.WxSubscribeVO;
import com.studyagent.security.UserContext;
import com.studyagent.service.WxPushService;
import com.studyagent.service.WxSubscribeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 微信订阅消息接口（Day 25）。
 *
 * <p>小程序端流程：在合适的时机调 {@code uni.requestSubscribeMessage}
 * 让用户授权（一次订阅换一次发送额度）→ 把结果上报到 {@code POST /wx/subscribe}
 * → 服务端记账。定时任务只对"已授权且还有额度"的用户投递消息。</p>
 */
@RestController
@RequestMapping("/wx")
@RequiredArgsConstructor
@Tag(name = "08-微信订阅消息", description = "订阅授权上报与状态查询")
@SecurityRequirement(name = "bearerAuth")
public class WxSubscribeController {

    private final WxSubscribeService wxSubscribeService;
    private final WxPushService wxPushService;
    private final WxProperties wxProperties;

    @GetMapping("/templates")
    @Operation(summary = "订阅模板清单", description = "返回当前配置的模板场景与模板 ID，小程序端据此发起授权")
    public Result<Map<String, String>> templates() {
        return Result.ok(new LinkedHashMap<>(wxProperties.getTemplates()));
    }

    @PostMapping("/subscribe")
    @Operation(summary = "上报订阅授权结果",
            description = "同意则发送额度 +1；拒绝只更新状态不回收已有额度。按 (用户, 场景) 幂等覆盖")
    public Result<List<WxSubscribeVO>> subscribe(@Valid @RequestBody WxSubscribeRequest request) {
        return Result.ok("订阅状态已更新", wxSubscribeService.record(UserContext.userId(), request));
    }

    @GetMapping("/subscribe")
    @Operation(summary = "我的订阅状态", description = "返回各场景是否已授权、剩余可发送次数")
    public Result<List<WxSubscribeVO>> mySubscribes() {
        return Result.ok(wxSubscribeService.listMy(UserContext.userId()));
    }

    @GetMapping("/push-log")
    @Operation(summary = "我的订阅消息推送记录",
            description = "最近若干条：success / failed / skipped（skipped 带原因，如 NO_OPENID / NOT_SUBSCRIBED / NO_QUOTA）")
    public Result<List<WxPushLogVO>> pushLogs(
            @Parameter(description = "条数，1~100") @RequestParam(defaultValue = "20") Integer limit) {
        int size = (limit == null || limit < 1) ? 20 : Math.min(limit, 100);
        return Result.ok(wxPushService.recentLogs(UserContext.userId(), size));
    }
}
