package com.studyagent.model.vo;

import com.studyagent.entity.ChatSession;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 问答会话视图。
 */
@Data
@Schema(description = "问答会话")
public class ChatSessionVO {

    @Schema(description = "会话 ID")
    private Long id;

    @Schema(description = "会话标题")
    private String title;

    @Schema(description = "消息条数")
    private Integer messageCount;

    @Schema(description = "最后一条消息时间")
    private LocalDateTime lastTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    public static ChatSessionVO from(ChatSession session) {
        ChatSessionVO vo = new ChatSessionVO();
        vo.setId(session.getId());
        vo.setTitle(session.getTitle());
        vo.setMessageCount(session.getMessageCount());
        vo.setLastTime(session.getLastTime());
        vo.setCreateTime(session.getCreateTime());
        return vo;
    }
}
