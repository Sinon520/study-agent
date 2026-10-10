package com.studyagent.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.studyagent.common.Result;
import com.studyagent.common.exception.BizException;
import com.studyagent.entity.ChatMessage;
import com.studyagent.entity.ChatSession;
import com.studyagent.entity.KbChunk;
import com.studyagent.entity.KbDocument;
import com.studyagent.model.dto.KbChunkSaveRequest;
import com.studyagent.model.dto.KbDocumentNamesRequest;
import com.studyagent.model.dto.KbStatusUpdateRequest;
import com.studyagent.service.ChatMessageService;
import com.studyagent.service.ChatSessionService;
import com.studyagent.service.KbChunkService;
import com.studyagent.service.KbDocumentService;
import com.studyagent.service.KnowledgeBaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 内部接口（仅供 Python AI 服务回调）。
 *
 * <p><b>安全模型</b>：这些接口不接受 JWT（Python 没有用户令牌），而是由
 * {@code InternalTokenFilter} 校验共享令牌 {@code X-Internal-Token} + 限制来源 IP。
 * 两者缺一不可：令牌防伪造，IP 限制防"令牌泄露后被公网利用"。</p>
 *
 * <p><b>为什么要有这些接口</b>：保持"Java 是唯一写入口"。
 * Python 写完 Milvus 后需要把切片元数据落 MySQL，但它不直连数据库，
 * 而是回调这里。好处是事务、审计、traceId、数据校验都只在一处实现。</p>
 */
@Slf4j
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
@Tag(name = "99-内部接口", description = "仅供 Python AI 服务回调，需 X-Internal-Token")
public class InternalController {

    private final KbDocumentService kbDocumentService;
    private final KbChunkService kbChunkService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ChatSessionService chatSessionService;
    private final ChatMessageService chatMessageService;

    // ------------------------------------------------------------------ 知识库

    /**
     * 批量回写切片元数据。
     *
     * <p>幂等处理：同一文档重复入库（重新解析）时先删旧切片再插新的，
     * 避免"重新解析一次，kb_chunk 里就多一份重复数据"。</p>
     */
    @PostMapping("/kb/chunks")
    @Transactional(rollbackFor = Exception.class)
    @Operation(summary = "回写切片元数据", description = "Python 解析完成后调用；同文档重复调用会覆盖旧切片")
    public Result<Integer> saveChunks(@Valid @RequestBody KbChunkSaveRequest request) {
        KbDocument document = kbDocumentService.getById(request.getDocumentId());
        if (document == null) {
            throw new BizException(Result.CODE_BAD_REQUEST, "文档不存在：" + request.getDocumentId());
        }
        if (request.getChunks() == null || request.getChunks().isEmpty()) {
            return Result.ok("切片为空，无需写入", 0);
        }

        // 覆盖式写入：先清掉该文档的旧切片
        kbChunkService.remove(Wrappers.<KbChunk>lambdaQuery()
                .eq(KbChunk::getDocumentId, request.getDocumentId()));

        List<KbChunk> entities = new ArrayList<>(request.getChunks().size());
        for (KbChunkSaveRequest.Item item : request.getChunks()) {
            KbChunk chunk = new KbChunk();
            chunk.setDocumentId(request.getDocumentId());
            // user_id 以文档记录为准，**不信任 Python 传来的值**，
            // 避免内部接口被误用后把切片挂到别的用户名下
            chunk.setUserId(document.getUserId());
            chunk.setChunkIndex(item.getChunkIndex());
            chunk.setContent(item.getContent());
            chunk.setPageNo(item.getPageNo());
            chunk.setVectorId(item.getVectorId());
            entities.add(chunk);
        }
        kbChunkService.saveBatch(entities, 200);

        log.info("回写切片完成 documentId={} 条数={}", request.getDocumentId(), entities.size());
        return Result.ok("写入成功", entities.size());
    }

    @PutMapping("/kb/document/{documentId}/status")
    @Operation(summary = "更新文档解析状态")
    public Result<Void> updateDocumentStatus(@PathVariable Long documentId,
                                            @Valid @RequestBody KbStatusUpdateRequest request) {
        KbDocument patch = new KbDocument();
        patch.setId(documentId);
        patch.setParseStatus(request.getParseStatus());
        if (request.getChunkCount() != null) {
            patch.setChunkCount(request.getChunkCount());
        }
        if (request.getErrorMsg() != null) {
            patch.setErrorMsg(request.getErrorMsg().length() <= 500
                    ? request.getErrorMsg()
                    : request.getErrorMsg().substring(0, 500));
        }
        boolean updated = kbDocumentService.updateById(patch);
        log.info("更新文档状态 documentId={} status={} chunkCount={} updated={}",
                documentId, request.getParseStatus(), request.getChunkCount(), updated);
        return Result.ok("状态已更新", null);
    }

    @PostMapping("/kb/document-names")
    @Operation(summary = "批量查询文档名", description = "Python 生成引用溯源时用")
    public Result<Map<Long, String>> documentNames(@Valid @RequestBody KbDocumentNamesRequest request) {
        return Result.ok(knowledgeBaseService.getDocumentNames(request.getDocumentIds()));
    }

    // ------------------------------------------------------------------ 问答

    /**
     * 保存一轮问答消息（幂等补写入口）。
     *
     * <p><b>当前 SSE 问答链路不调用本接口</b>：落库已由 {@code ChatServiceImpl.askStream}
     * 独占完成（Java 透传时既写 user 消息，也写含 citations 的 assistant 消息，
     * 连接中断时保存部分内容）。Python 曾在流结束前调用这里"先写一遍"，
     * 与 Java 的写入形成竞态 —— 下方的 {@link #saveIfAbsent} 会赢不了
     * "两边同时查、都没查到、于是都插"的时序，导致每条 assistant 存两份。
     * 因此本接口保留为**非 SSE 场景**（补偿任务、批量导入）的补写入口，
     * 自带同角色同内容的幂等保护。</p>
     *
     * <p>注意这里同时更新会话的消息数与最后时间，否则会话列表的排序与条数会一直是 0。</p>
     */
    @PostMapping("/chat/messages")
    @Transactional(rollbackFor = Exception.class)
    @Operation(summary = "保存一轮问答消息（幂等补写）", description = "非 SSE 场景的补写入口；SSE 链路由 Java 透传时独占落库")
    public Result<Long> saveChatMessages(@RequestBody Map<String, Object> payload) {
        Long userId = asLong(payload.get("userId"));
        Long sessionId = asLong(payload.get("sessionId"));
        if (userId == null || sessionId == null) {
            throw new BizException(Result.CODE_BAD_REQUEST, "userId 与 sessionId 不能为空");
        }

        ChatSession session = chatSessionService.getById(sessionId);
        if (session == null) {
            throw new BizException(Result.CODE_BAD_REQUEST, "会话不存在：" + sessionId);
        }
        if (!session.getUserId().equals(userId)) {
            throw new BizException(Result.CODE_FORBIDDEN, "会话与用户不匹配，拒绝写入");
        }

        // 幂等保护：同 session 同 role 同内容不重复插。
        // 注意它的适用边界 —— 只对"单一调用方"有效；如果两个写入方并发进入，
        // 双方的存在性检查都可能在对方插入前通过，幂等就失效了（项目实际踩过）。
        int saved = 0;
        saved += saveIfAbsent(session, userId, "user", asString(payload.get("question")), null, null);
        saved += saveIfAbsent(session, userId, "assistant", asString(payload.get("answer")),
                asString(payload.get("reasoning")),
                payload.get("citations") == null ? null : toJson(payload.get("citations")));

        ChatSession patch = new ChatSession();
        patch.setId(sessionId);
        patch.setMessageCount((session.getMessageCount() == null ? 0 : session.getMessageCount()) + saved);
        patch.setLastTime(LocalDateTime.now());
        chatSessionService.updateById(patch);

        log.info("内部接口保存问答消息 sessionId={} 新增={}", sessionId, saved);
        return Result.ok("已保存", (long) saved);
    }

    @GetMapping("/chat/history")
    @Operation(summary = "取会话历史", description = "Python 拼多轮 prompt 时用，按时间正序")
    public Result<List<Map<String, Object>>> chatHistory(@RequestParam Long sessionId,
                                                        @RequestParam(defaultValue = "12") Integer limit) {
        ChatSession session = chatSessionService.getById(sessionId);
        if (session == null) {
            return Result.ok(Collections.emptyList());
        }
        int size = Math.max(1, Math.min(limit == null ? 12 : limit, 50));
        List<ChatMessage> messages = chatMessageService.list(
                Wrappers.<ChatMessage>lambdaQuery()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByDesc(ChatMessage::getId)
                        .last("limit " + size));

        // 数据库查出来是倒序（取最近 N 条），拼 prompt 要正序
        List<Map<String, Object>> result = new ArrayList<>(messages.size());
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            result.add(Map.of(
                    "role", message.getRole() == null ? "" : message.getRole(),
                    "content", message.getContent() == null ? "" : message.getContent()));
        }
        return Result.ok(result);
    }

    // ------------------------------------------------------------------ 工具

    /** 只有不存在同角色同内容的消息时才插入，避免 Java/Python 两侧重复落库 */
    private int saveIfAbsent(ChatSession session, Long userId, String role,
                            String content, String reasoning, String citationsJson) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        long exists = chatMessageService.count(Wrappers.<ChatMessage>lambdaQuery()
                .eq(ChatMessage::getSessionId, session.getId())
                .eq(ChatMessage::getRole, role)
                .eq(ChatMessage::getContent, content));
        if (exists > 0) {
            return 0;
        }
        ChatMessage message = new ChatMessage();
        message.setSessionId(session.getId());
        message.setUserId(userId);
        message.setRole(role);
        message.setContent(content);
        message.setReasoningContent(reasoning);
        message.setCitations(citationsJson);
        message.setTokenCount(0);
        chatMessageService.save(message);
        return 1;
    }

    private String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
