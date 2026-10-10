package com.studyagent.model.vo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.entity.ChatMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 问答消息视图。
 *
 * <p>{@code citations} 在数据库里是 JSON 字符串，这里在返回前解析成结构化列表，
 * 免得每个前端都自己 {@code JSON.parse} 一遍。解析失败降级为空列表，
 * 不让一条脏数据把整个会话历史打不出来。</p>
 */
@Data
@Schema(description = "问答消息")
public class ChatMessageVO {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Schema(description = "消息 ID")
    private Long id;

    @Schema(description = "所属会话")
    private Long sessionId;

    @Schema(description = "角色：user/assistant/system")
    private String role;

    @Schema(description = "消息正文")
    private String content;

    @Schema(description = "思考过程（reasoning_content）")
    private String reasoningContent;

    @Schema(description = "引用溯源列表")
    private List<Map<String, Object>> citations;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    public static ChatMessageVO from(ChatMessage message) {
        ChatMessageVO vo = new ChatMessageVO();
        vo.setId(message.getId());
        vo.setSessionId(message.getSessionId());
        vo.setRole(message.getRole());
        vo.setContent(message.getContent());
        vo.setReasoningContent(message.getReasoningContent());
        vo.setCitations(parseCitations(message.getCitations()));
        vo.setCreateTime(message.getCreateTime());
        return vo;
    }

    private static List<Map<String, Object>> parseCitations(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
