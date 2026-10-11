package com.studyagent.model.vo;

import com.studyagent.entity.WxPushLog;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 微信推送日志视图。
 *
 * <p>定时推送/异步消费这类链路"看不见"，一旦用户说"我没收到通知"，
 * 查这张表就能区分：是没授权、额度用完，还是真的发送失败。</p>
 */
@Data
@Schema(description = "微信推送日志")
public class WxPushLogVO {

    @Schema(description = "日志 ID")
    private Long id;

    @Schema(description = "模板场景：reminder/review/report")
    private String templateKey;

    @Schema(description = "微信模板 ID")
    private String templateId;

    @Schema(description = "点击跳转页面")
    private String page;

    @Schema(description = "success/failed/skipped")
    private String status;

    @Schema(description = "是否为 mock 发送（无真实 AppID 期间为 true）")
    private Boolean mock;

    @Schema(description = "错误码或跳过原因")
    private String errorCode;

    @Schema(description = "错误/跳过说明")
    private String errorMsg;

    @Schema(description = "消息内容摘要")
    private String content;

    @Schema(description = "推送时间")
    private LocalDateTime createTime;

    public static WxPushLogVO from(WxPushLog log) {
        WxPushLogVO vo = new WxPushLogVO();
        vo.setId(log.getId());
        vo.setTemplateKey(log.getTemplateKey());
        vo.setTemplateId(log.getTemplateId());
        vo.setPage(log.getPage());
        vo.setStatus(log.getStatus());
        vo.setMock(log.getMock() != null && log.getMock() == 1);
        vo.setErrorCode(log.getErrorCode());
        vo.setErrorMsg(log.getErrorMsg());
        vo.setContent(log.getContent());
        vo.setCreateTime(log.getCreateTime());
        return vo;
    }
}
