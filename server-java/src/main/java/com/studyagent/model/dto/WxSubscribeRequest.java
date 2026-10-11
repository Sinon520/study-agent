package com.studyagent.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 小程序订阅消息授权结果上报（POST /wx/subscribe）。
 *
 * <p>小程序端调 {@code uni.requestSubscribeMessage} 拿到的是
 * {@code {模板ID: "accept"/"reject"/...}}，这里要求前端把「模板ID → 业务场景 key」
 * 映射好再上报：消息内容属于业务语义，模板 ID 属于配置（换模板不必动业务代码）。</p>
 */
@Data
public class WxSubscribeRequest {

    @Valid
    @NotEmpty(message = "订阅授权结果不能为空")
    @Size(max = 8, message = "一次最多上报 8 个模板")
    private List<Item> items = new ArrayList<>();

    /** 单个模板的授权结果 */
    @Data
    public static class Item {

        /**
         * 业务场景 key：{@code reminder}（学习提醒）/ {@code review}（错题复习）/
         * {@code report}（复盘报告）。服务端按它从 {@code wx.templates.*} 取模板 ID，
         * 不信任前端传的 templateId（避免伪造模板）。
         */
        @NotBlank(message = "templateKey 不能为空")
        private String templateKey;

        /** 微信返回的授权结果，如 accept / reject / ban / filter */
        @NotBlank(message = "授权结果 status 不能为空")
        private String status;

        /** 小程序端拿到的模板 ID（仅记录，用于排查"模板配错"问题） */
        private String templateId;
    }
}
