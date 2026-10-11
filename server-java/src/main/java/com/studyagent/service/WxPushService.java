package com.studyagent.service;

import com.studyagent.model.vo.WxPushLogVO;

import java.util.List;
import java.util.Map;

/**
 * 微信订阅消息推送 业务接口（Day 25）。
 *
 * <p>对外的入口是 {@link #push}：它把"能不能发、发了没、为什么没发"全部收敛到一条
 * 记录里（{@code wx_push_log}），调用方（MQ 消费者）只需要根据返回的
 * {@link PushResult#sent()} 决定要不要抛异常重试。</p>
 */
public interface WxPushService {

    /**
     * 取当前可用的 access_token（优先 Redis 缓存）。
     *
     * <p>微信对 access_token 有**调用频率限制**，且**刷新后旧 token 有 5 分钟宽限期**，
     * 因此必须缓存（默认 2 小时过期，这里提前 5 分钟刷新留余量），
     * 并用 Redis 锁保证**同一时刻只有一个线程去刷新**（否则并发风暴会把刷新额度打满，
     * 还会出现"刚刷新就被别人覆盖"的抖动）。</p>
     *
     * @throws com.studyagent.common.exception.BizException 刷新失败（可重试）
     */
    String getAccessToken();

    /**
     * 推送一条订阅消息。
     *
     * @param userId      接收用户（用来查 openid）
     * @param templateKey 业务场景：reminder/review/report
     * @param page        点击消息跳转的小程序页面
     * @param data        模板数据 {@code {"thing1": {"value": "..."}}}
     * @return 推送结果；{@code sent=false} 表示终态跳过，不要重试
     * @throws com.studyagent.common.exception.BizException 可重试的失败
     */
    PushResult push(Long userId, String templateKey, String page, Map<String, Object> data);

    /**
     * 最近若干条推送记录（倒序）。
     *
     * <p>存在的意义：定时推送是"看不见"的异步链路，出问题时最需要回答
     * "到底发没发、为什么没发"。这张表 + 这个接口就是答案。</p>
     */
    List<WxPushLogVO> recentLogs(Long userId, int limit);
}
