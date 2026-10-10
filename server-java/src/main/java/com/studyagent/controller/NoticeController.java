package com.studyagent.controller;

import com.studyagent.common.Result;
import com.studyagent.model.dto.BatchRequest;
import com.studyagent.model.dto.PageQuery;
import com.studyagent.model.vo.NoticeMessageVO;
import com.studyagent.model.vo.PageResult;
import com.studyagent.security.UserContext;
import com.studyagent.service.NoticeMessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站内通知接口。
 *
 * <p>本周只做列表与已读；推送触发（定时提醒、报告生成、微信订阅消息）
 * 在第 4 周由 RocketMQ 消费者调用 {@code NoticeMessageService#push} 产生。</p>
 */
@RestController
@RequestMapping("/notice")
@RequiredArgsConstructor
@Tag(name = "06-站内通知", description = "通知列表、未读数、标记已读")
@SecurityRequirement(name = "bearerAuth")
public class NoticeController {

    private final NoticeMessageService noticeMessageService;

    @GetMapping("/page")
    @Operation(summary = "分页查询通知", description = "未读优先，再按时间倒序")
    public Result<PageResult<NoticeMessageVO>> page(
            PageQuery query,
            @Parameter(description = "类型：提醒/复习/报告") @RequestParam(required = false) String type,
            @Parameter(description = "已读状态：0 未读 / 1 已读") @RequestParam(required = false) Integer readFlag) {
        return Result.ok(noticeMessageService.pageNotices(UserContext.userId(), query, type, readFlag));
    }

    @GetMapping("/unread-count")
    @Operation(summary = "未读数量", description = "PC 端角标 / 小程序 tabBar 红点，建议前端轮询或进入页面时调一次")
    public Result<Long> unreadCount() {
        return Result.ok(noticeMessageService.unreadCount(UserContext.userId()));
    }

    @PutMapping("/{id}/read")
    @Operation(summary = "标记单条已读")
    public Result<Void> markRead(@PathVariable Long id) {
        noticeMessageService.markRead(UserContext.userId(), id);
        return Result.ok("已标记为已读", null);
    }

    @PutMapping("/read/batch")
    @Operation(summary = "批量标记已读")
    public Result<Integer> markReadBatch(@Valid @RequestBody BatchRequest request) {
        int count = noticeMessageService.markReadBatch(UserContext.userId(), request.getIds());
        return Result.ok("已标记 " + count + " 条", count);
    }

    @PutMapping("/read/all")
    @Operation(summary = "全部标记已读")
    public Result<Integer> markAllRead() {
        int count = noticeMessageService.markAllRead(UserContext.userId());
        return Result.ok("已标记 " + count + " 条", count);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除通知", description = "逻辑删除")
    public Result<Void> delete(@PathVariable Long id) {
        noticeMessageService.deleteNotice(UserContext.userId(), id);
        return Result.ok("删除成功", null);
    }
}
