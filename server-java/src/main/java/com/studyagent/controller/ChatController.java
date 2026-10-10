package com.studyagent.controller;

import com.studyagent.common.Result;
import com.studyagent.entity.ChatMessage;
import com.studyagent.entity.ChatSession;
import com.studyagent.model.dto.ChatAskRequest;
import com.studyagent.model.dto.PageQuery;
import com.studyagent.model.vo.ChatMessageVO;
import com.studyagent.model.vo.ChatSessionVO;
import com.studyagent.model.vo.PageResult;
import com.studyagent.security.UserContext;
import com.studyagent.service.ChatMessageService;
import com.studyagent.service.ChatService;
import com.studyagent.service.ChatSessionService;
import com.studyagent.common.exception.BizException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 问答接口。
 *
 * <p>{@code POST /chat/ask} 是 SSE 端点：Java 校验 JWT → 转发 Python → 透传流给前端，
 * 并在结束后把 user/assistant 两条消息落库。</p>
 */
@Slf4j
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
@Tag(name = "08-AI 问答", description = "SSE 流式问答、会话与历史消息")
@SecurityRequirement(name = "bearerAuth")
public class ChatController {

    private final ChatService chatService;
    private final ChatSessionService chatSessionService;
    private final ChatMessageService chatMessageService;

    /**
     * 流式问答（SSE）。
     *
     * <p>响应不是统一返回体，而是 {@code text/event-stream}。帧协议见
     * {@code server-ai/app/routers/rag.py} 的模块注释。</p>
     */
    @PostMapping(value = "/ask", produces = "text/event-stream;charset=UTF-8")
    @Operation(summary = "流式问答（SSE）",
            description = "事件帧：start / reasoning / message / citations / error，以 data: [DONE] 结束。"
                    + "注意：一旦开始推送就无法再改成 JSON 错误，所以检索阶段的失败会以 HTTP 错误返回")
    public void ask(@Valid @RequestBody ChatAskRequest request,
                    jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        chatService.askStream(UserContext.userId(), request, response);
    }

    @GetMapping("/session/page")
    @Operation(summary = "分页查询会话")
    public Result<PageResult<ChatSessionVO>> pageSessions(PageQuery query) {
        Long userId = UserContext.userId();
        // 显式指定泛型：toPage() 的返回类型需要靠目标类型推断，
        // 直接写 var 会推成 Page<Object>，与 IService.page 的签名对不上
        var page = query.<ChatSession>toPage();
        var result = chatSessionService.page(page,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<ChatSession>lambdaQuery()
                        .eq(ChatSession::getUserId, userId)
                        .orderByDesc(ChatSession::getLastTime)
                        .orderByDesc(ChatSession::getId));
        return Result.ok(PageResult.of(result, ChatSessionVO::from));
    }

    @GetMapping("/session/{sessionId}/messages")
    @Operation(summary = "会话消息列表", description = "按时间正序返回，含引用溯源 JSON")
    public Result<List<ChatMessageVO>> listMessages(@PathVariable Long sessionId) {
        Long userId = UserContext.userId();
        requireOwnedSession(userId, sessionId);
        List<ChatMessage> messages = chatMessageService.list(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<ChatMessage>lambdaQuery()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByAsc(ChatMessage::getId));
        return Result.ok(messages.stream().map(ChatMessageVO::from).toList());
    }

    @DeleteMapping("/session/{sessionId}")
    @Operation(summary = "删除会话", description = "逻辑删除会话，消息保留（便于回溯）")
    public Result<Void> deleteSession(@PathVariable Long sessionId) {
        requireOwnedSession(UserContext.userId(), sessionId);
        chatSessionService.removeById(sessionId);
        return Result.ok("删除成功", null);
    }

    private void requireOwnedSession(Long userId, Long sessionId) {
        ChatSession session = chatSessionService.getById(sessionId);
        if (session == null) {
            throw new BizException(com.studyagent.common.Result.CODE_BAD_REQUEST,
                    "会话不存在：" + sessionId);
        }
        if (!session.getUserId().equals(userId)) {
            log.warn("水平越权拦截：userId={} 试图访问 sessionId={}（属于 userId={}）",
                    userId, sessionId, session.getUserId());
            throw new BizException(com.studyagent.common.Result.CODE_FORBIDDEN, "无权访问该会话");
        }
    }
}
