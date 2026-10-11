package com.studyagent.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.Result;
import com.studyagent.common.exception.BizException;
import com.studyagent.config.WxProperties;
import com.studyagent.entity.SysUser;
import com.studyagent.entity.WxPushLog;
import com.studyagent.mapper.WxPushLogMapper;
import com.studyagent.service.PushResult;
import com.studyagent.service.SysUserService;
import com.studyagent.service.WxPushService;
import com.studyagent.service.WxSubscribeService;
import com.studyagent.model.vo.WxPushLogVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 微信订阅消息推送 业务实现（Day 25）。
 *
 * <h3>两条容易踩的规则（面试可讲）</h3>
 * <ol>
 *   <li><b>access_token 必须缓存</b>：微信限制获取频率（日调用上限 + 并发限制），
 *       且刷新后旧 token 还有 5 分钟宽限期。这里缓存到 Redis，TTL = expires_in - 300s
 *       （提前 5 分钟刷新，避免边界失效）。</li>
 *   <li><b>刷新要加锁</b>：缓存同时过期时，多个消费者线程会一起发现"缓存没了"，
 *       然后一起调刷新接口 —— 既浪费额度，又会出现"先刷到的 token 被后刷的覆盖"
 *       导致部分请求用到已失效 token。用 Redis SETNX 锁 + 双重检查，
 *       保证同一时刻只有一个线程刷新，其余读缓存。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WxPushServiceImpl implements WxPushService {

    /** 刷新锁 TTL：覆盖一次 token 请求的最坏耗时，超时自动释放避免死锁 */
    private static final Duration LOCK_TTL = Duration.ofSeconds(20);
    /** 提前刷新的余量（秒）：微信 token 有效期 7200s，提前 5 分钟换 */
    private static final long REFRESH_MARGIN_SECONDS = 300L;
    private static final long DEFAULT_EXPIRES_IN = 7200L;
    /** 拿不到锁时，等待别人刷新结果的最大时长 */
    private static final int WAIT_TIMES = 12;

    private final WxProperties wxProperties;
    private final SysUserService sysUserService;
    private final WxSubscribeService wxSubscribeService;
    private final WxPushLogMapper wxPushLogMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public String getAccessToken() {
        // mock 模式不调微信：本地没有真实 AppID/模板，调了必然失败
        if (wxProperties.isMockEnabled()) {
            return "mock-access-token";
        }

        String cacheKey = accessTokenKey();
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StringUtils.hasText(cached)) {
            return cached;
        }

        String lockKey = cacheKey + ":lock";
        boolean locked = Boolean.TRUE.equals(
                stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", LOCK_TTL));
        if (!locked) {
            return waitForRefresh(cacheKey);
        }
        try {
            // 双重检查：等锁期间可能已被别的线程刷新好
            cached = stringRedisTemplate.opsForValue().get(cacheKey);
            if (StringUtils.hasText(cached)) {
                return cached;
            }
            return requestAccessToken(cacheKey);
        } finally {
            stringRedisTemplate.delete(lockKey);
        }
    }

    @Override
    public PushResult push(Long userId, String templateKey, String page, Map<String, Object> data) {
        String templateId = wxProperties.getTemplates().get(templateKey);

        SysUser user = sysUserService.getById(userId);
        if (user == null) {
            return skipped(userId, templateKey, templateId, page, data, "NO_USER", "用户不存在");
        }
        if (!StringUtils.hasText(user.getOpenid())) {
            return skipped(userId, templateKey, templateId, page, data,
                    "NO_OPENID", "用户未绑定小程序，跳过推送");
        }
        // 授权状态与额度分开判定：两者是不同的终态原因，混在一起没法排查
        if (!wxSubscribeService.hasApproved(userId, templateKey)) {
            return skipped(userId, templateKey, templateId, page, data,
                    "NOT_SUBSCRIBED", "用户未授权该订阅模板（或已拒绝）");
        }
        if (!wxSubscribeService.consumeQuota(userId, templateKey)) {
            return skipped(userId, templateKey, templateId, page, data,
                    "NO_QUOTA", "订阅额度已用完（一次订阅一次发送）");
        }

        // 走到这里才真正发送：额度已扣，避免并发超发

        if (wxProperties.isMockEnabled()) {
            log.info("[mock 订阅消息] userId={} openid={} templateKey={} templateId={} data={}",
                    userId, user.getOpenid(), templateKey, templateId, data);
            return record(userId, templateKey, templateId, page, data,
                    WxPushLog.STATUS_SUCCESS, true, null, summarize(data));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("touser", user.getOpenid());
        body.put("template_id", templateId);
        if (StringUtils.hasText(page)) {
            body.put("page", page);
        }
        body.put("data", data == null ? Map.of() : data);
        body.put("miniprogram_state", wxProperties.getMiniprogramState());
        body.put("lang", "zh_CN");

        try {
            String raw = client().post()
                    .uri(wxProperties.getSubscribeSendUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode root = raw == null || raw.isBlank() ? null : objectMapper.readTree(raw);
            int errcode = root == null ? 0 : root.path("errcode").asInt(0);
            if (errcode == 0) {
                return record(userId, templateKey, templateId, page, data,
                        WxPushLog.STATUS_SUCCESS, false, null, summarize(data));
            }
            String errMsg = root == null ? "空响应" : root.path("errmsg").asText("未知错误");
            // 终态错误码：用户拒收(43101)、模板 ID 非法(40037)、openid 无效(40003)
            // —— 重投多少次都不会成功，直接记 skipped 放行
            if (errcode == 43101 || errcode == 40037 || errcode == 40003) {
                return skipped(userId, templateKey, templateId, page, data,
                        String.valueOf(errcode), "终态失败（不重试）：" + errMsg);
            }
            throw new BizException(Result.CODE_FAIL,
                    "微信订阅消息发送失败（" + errcode + "）：" + errMsg);
        } catch (Exception exc) {
            if (exc instanceof BizException biz) {
                throw biz;
            }
            throw new BizException(Result.CODE_FAIL, "微信订阅消息发送异常：" + exc.getMessage());
        }
    }

    @Override
    public List<WxPushLogVO> recentLogs(Long userId, int limit) {
        int size = Math.max(1, Math.min(limit, 100));
        return wxPushLogMapper.recent(userId, size).stream()
                .map(WxPushLogVO::from)
                .toList();
    }

    // ------------------------------------------------------------------ 内部

    /** Redis 缓存 key：按 appid 区分，避免换小程序环境时串用同一个 token */
    private String accessTokenKey() {
        return "wx:access_token:" + wxProperties.getAppid();
    }

    /**
     * 终态跳过：不影响业务正确性，但必须落日志 ——
     * 否则"用户说没收到通知"时无从判断是没授权、没额度还是真失败。
     *
     * <p>{@code mock} 字段记录"跳过发生在哪种通道下"：mock 期间为 true，
     * 切真实凭证后同一批数据仍能区分环境，便于对照排查。</p>
     */
    private PushResult skipped(Long userId, String templateKey, String templateId, String page,
                            Map<String, Object> data, String code, String reason) {
        log.info("跳过微信订阅消息 userId={} templateKey={} code={} reason={}",
                userId, templateKey, code, reason);
        return record(userId, templateKey, templateId, page, data,
                WxPushLog.STATUS_SKIPPED, wxProperties.isMockEnabled(), code, reason);
    }

    /** 落一条推送日志，并把结果返回给调用方（决定是否抛异常重试） */
    private PushResult record(Long userId, String templateKey, String templateId, String page,
                           Map<String, Object> data, String status, boolean mock,
                           String errorCode, String detail) {
        WxPushLog pushLog = new WxPushLog();
        pushLog.setUserId(userId);
        pushLog.setTemplateKey(templateKey);
        pushLog.setTemplateId(templateId);
        pushLog.setPage(page);
        pushLog.setStatus(status);
        pushLog.setMock(mock ? 1 : 0);
        pushLog.setErrorCode(errorCode);
        pushLog.setErrorMsg(WxPushLog.STATUS_FAILED.equals(status) ? detail : null);
        pushLog.setContent(detail);
        wxPushLogMapper.insert(pushLog);
        return new PushResult(WxPushLog.STATUS_SUCCESS.equals(status), mock, status, detail);
    }

    /** 拿到锁前别人已在刷新：轮询等一小会儿读缓存，超时抛可重试异常 */
    private String waitForRefresh(String cacheKey) {
        for (int i = 0; i < WAIT_TIMES; i++) {
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BizException(Result.CODE_FAIL, "等待 access_token 刷新被中断");
            }
            String cached = stringRedisTemplate.opsForValue().get(cacheKey);
            if (StringUtils.hasText(cached)) {
                return cached;
            }
        }
        throw new BizException(Result.CODE_FAIL, "等待 access_token 刷新超时，稍后重试");
    }

    private String requestAccessToken(String cacheKey) {
        String url = UriComponentsBuilder.fromUriString(wxProperties.getAccessTokenUrl())
                .queryParam("grant_type", "client_credential")
                .queryParam("appid", wxProperties.getAppid())
                .queryParam("secret", wxProperties.getSecret())
                .toUriString();

        JsonNode root;
        try {
            root = client().get().uri(url).retrieve().body(JsonNode.class);
        } catch (Exception exc) {
            throw new BizException(Result.CODE_FAIL, "请求 access_token 失败：" + exc.getMessage());
        }
        if (root == null) {
            throw new BizException(Result.CODE_FAIL, "请求 access_token 失败：空响应");
        }
        JsonNode token = root.path("access_token");
        if (token.isMissingNode() || token.asText("").isBlank()) {
            int errcode = root.path("errcode").asInt(-1);
            String errmsg = root.path("errmsg").asText(root.toString());
            throw new BizException(Result.CODE_FAIL,
                    "获取 access_token 失败（" + errcode + "）：" + errmsg);
        }

        long expiresIn = root.path("expires_in").asLong(DEFAULT_EXPIRES_IN);
        // 留余量 + 设下限，避免极端配置导致 TTL 为负（Redis 会直接报错）
        long ttl = Math.max(60L, expiresIn - REFRESH_MARGIN_SECONDS);
        stringRedisTemplate.opsForValue().set(cacheKey, token.asText(), Duration.ofSeconds(ttl));
        log.info("access_token 已刷新并写入缓存 ttl={}s", ttl);
        return token.asText();
    }

    /** 把模板数据打平成一句摘要，落日志便于人工核对"到底发了什么" */
    private String summarize(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested && nested.get("value") != null) {
                sb.append(nested.get("value")).append(" / ");
            }
        }
        String text = sb.toString();
        return text.endsWith(" / ") ? text.substring(0, text.length() - 3) : text;
    }

    private RestClient client() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(20));
        return RestClient.builder().requestFactory(factory).build();
    }
}
