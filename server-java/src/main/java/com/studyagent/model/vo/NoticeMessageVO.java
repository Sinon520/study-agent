package com.studyagent.model.vo;

import com.studyagent.entity.NoticeMessage;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内通知视图。
 */
@Data
@Schema(description = "站内通知")
public class NoticeMessageVO {

    @Schema(description = "通知 ID")
    private Long id;

    @Schema(description = "类型：提醒/复习/报告")
    private String type;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "内容")
    private String content;

    @Schema(description = "渠道：PC/小程序")
    private String channel;

    @Schema(description = "是否已读")
    private Boolean read;

    @Schema(description = "业务 ID，用于前端跳转")
    private Long bizId;

    @Schema(description = "发送时间")
    private LocalDateTime sendTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    public static NoticeMessageVO from(NoticeMessage message) {
        NoticeMessageVO vo = new NoticeMessageVO();
        vo.setId(message.getId());
        vo.setType(message.getType());
        vo.setTitle(message.getTitle());
        vo.setContent(message.getContent());
        vo.setChannel(message.getChannel());
        vo.setRead(message.getReadFlag() != null && message.getReadFlag() == 1);
        vo.setBizId(message.getBizId());
        vo.setSendTime(message.getSendTime());
        vo.setCreateTime(message.getCreateTime());
        return vo;
    }
}
