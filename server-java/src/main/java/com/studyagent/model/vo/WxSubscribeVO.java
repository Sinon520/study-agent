package com.studyagent.model.vo;

import com.studyagent.entity.WxSubscribe;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 微信订阅消息授权视图。
 *
 * <p>{@code authorized} 是"当前能不能发"的唯一判据：既要最近一次授权是同意，
 * 也要还有剩余额度（微信语义：一次订阅 = 一次发送额度）。</p>
 */
@Data
@Schema(description = "微信订阅消息授权")
public class WxSubscribeVO {

    @Schema(description = "记录 ID")
    private Long id;

    @Schema(description = "模板场景：reminder/review/report")
    private String templateKey;

    @Schema(description = "微信模板 ID")
    private String templateId;

    @Schema(description = "最近一次授权结果：1同意 0拒绝/失效")
    private Integer status;

    @Schema(description = "剩余可发送次数")
    private Integer acceptCount;

    @Schema(description = "当前是否可推送")
    private Boolean authorized;

    @Schema(description = "最近一次授权时间")
    private LocalDateTime authorizeTime;

    public static WxSubscribeVO from(WxSubscribe record) {
        WxSubscribeVO vo = new WxSubscribeVO();
        vo.setId(record.getId());
        vo.setTemplateKey(record.getTemplateKey());
        vo.setTemplateId(record.getTemplateId());
        vo.setStatus(record.getStatus());
        vo.setAcceptCount(record.getAcceptCount());
        vo.setAuthorizeTime(record.getAuthorizeTime());
        int remaining = record.getAcceptCount() == null ? 0 : record.getAcceptCount();
        vo.setAuthorized(record.getStatus() != null && record.getStatus() == 1 && remaining > 0);
        return vo;
    }
}
