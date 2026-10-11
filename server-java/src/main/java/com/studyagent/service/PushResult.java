package com.studyagent.service;

/**
 * 微信推送结果。
 *
 * <p>三种终态对应 {@code wx_push_log.status}：</p>
 * <ul>
 *   <li>{@code success}：真实发送成功，或 mock 模式下模拟发送（{@code mock=true}）</li>
 *   <li>{@code skipped}：终态跳过（无 openid / 未授权 / 额度不足），**不应重试**，
 *       因为重试多少次结果都一样</li>
 *   <li>{@code failed}：可重试失败（网络异常、微信 5xx 等），调用方抛异常交给 MQ 重试</li>
 * </ul>
 *
 * @param sent  是否真的发出去了
 * @param mock  是否为模拟发送（无真实 AppID 期间为 true）
 * @param status 落库状态
 * @param detail 成功摘要 / 失败与跳过原因
 */
public record PushResult(boolean sent, boolean mock, String status, String detail) {

    public static PushResult success(String detail, boolean mock) {
        return new PushResult(true, mock, "success", detail);
    }

    public static PushResult skipped(String reason) {
        return new PushResult(false, true, "skipped", reason);
    }
}
