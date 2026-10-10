package com.studyagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 问答消息表
 */
@Data
@TableName("chat_message")
@Schema(description = "问答消息")
public class ChatMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "所属会话")
    private Long sessionId;

    @Schema(description = "所属用户ID")
    private Long userId;

    @Schema(description = "角色：user/assistant/system")
    private String role;

    @Schema(description = "消息内容")
    private String content;

    @Schema(description = "思考过程（reasoning_content，DeepSeek 推理模型）")
    private String reasoningContent;

    /** MySQL json 列，JDBC 以字符串读取 */
    @Schema(description = "引用来源 JSON 数组")
    private String citations;

    @Schema(description = "token 数量")
    private Integer tokenCount;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
