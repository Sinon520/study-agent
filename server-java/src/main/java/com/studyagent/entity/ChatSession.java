package com.studyagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 问答会话表
 */
@Data
@TableName("chat_session")
@Schema(description = "问答会话")
public class ChatSession implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "所属用户")
    private Long userId;

    @Schema(description = "会话标题（取首条消息前 30 字）")
    private String title;

    @Schema(description = "消息数量")
    private Integer messageCount;

    @TableLogic
    @Schema(description = "逻辑删除")
    private Integer deleted;

    @Schema(description = "最后一条消息时间")
    private LocalDateTime lastTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
