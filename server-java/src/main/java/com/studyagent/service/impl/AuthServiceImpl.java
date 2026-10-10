package com.studyagent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.Result;
import com.studyagent.common.exception.BizException;
import com.studyagent.config.JwtProperties;
import com.studyagent.config.WxProperties;
import com.studyagent.entity.SysUser;
import com.studyagent.model.dto.LoginRequest;
import com.studyagent.model.dto.RegisterRequest;
import com.studyagent.model.dto.WxLoginRequest;
import com.studyagent.model.vo.LoginVO;
import com.studyagent.model.vo.UserInfoVO;
import com.studyagent.security.JwtTokenProvider;
import com.studyagent.service.AuthService;
import com.studyagent.service.SysUserService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 认证服务实现。
 *
 * <h3>refresh token 的存储设计</h3>
 * <ul>
 *   <li>key = {@code auth:refresh:<userId>:<jti>}，value = 签发时间戳，TTL = refresh 有效期</li>
 *   <li>key 里带 jti 而不是只带 userId：同一用户多端登录互不影响</li>
 *   <li>刷新时做<b>轮换</b>：用 Redis 的 {@code DEL} 原子抢占，旧 token 用过即废</li>
 *   <li>登出时删掉当前 jti，实现"单端下线"</li>
 * </ul>
 *
 * <h3>两个安全细节（容易被面试追问）</h3>
 * <ol>
 *   <li><b>防账号枚举的时序侧信道</b>：用户不存在时也要跑一次 BCrypt 校验（对着哑元密文），
 *       否则"用户不存在"直接短路返回，响应时间比"密码错误"短一个数量级，
 *       攻击者靠计时就能枚举出哪些用户名真实存在。</li>
 *   <li><b>轮换的原子性</b>：如果先 {@code GET} 判断存在、再 {@code DEL}，
 *       两个并发请求会都读到非空、都成功，轮换被击穿（refresh token 双花）。
 *       这里直接用 {@code DEL} 的布尔返回值做抢占：只有抢到的那个请求能继续。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    /**
     * 哑元 BCrypt 密文：仅用于"用户不存在"时消耗与真实校验相当的时间。
     * <p>它是任意一个 BCrypt 串，不匹配任何真实密码，也不会被写进数据库。</p>
     */
    private static final String DUMMY_BCRYPT_HASH =
            "$2a$10$2wkCrDJsWfTF4lAFvfinJe6A8Yc4nLEizy32iL3HXG6rY6X7M9lFa";

    private final SysUserService sysUserService;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final JwtProperties jwtProperties;
    private final WxProperties wxProperties;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    // ------------------------------------------------------------------ 注册

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserInfoVO register(RegisterRequest request) {
        String username = request.getUsername().trim();

        // 先查一次给出友好提示；并发下仍有唯一索引兜底
        if (existsByUsername(username)) {
            throw new BizException(Result.CODE_BAD_REQUEST, "用户名已被注册：" + username);
        }

        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(StringUtils.hasText(request.getNickname()) ? request.getNickname().trim() : username);
        user.setPhone(StringUtils.hasText(request.getPhone()) ? request.getPhone() : null);
        user.setExamType(request.getExamType());
        user.setStatus(1);

        try {
            sysUserService.save(user);
        } catch (DuplicateKeyException e) {
            // 唯一索引 uk_username 兜底并发注册
            throw new BizException(Result.CODE_BAD_REQUEST, "用户名已被注册：" + username);
        }

        log.info("用户注册成功 userId={} username={}", user.getId(), user.getUsername());
        return UserInfoVO.from(user);
    }

    // ------------------------------------------------------------------ 登录

    @Override
    public LoginVO login(LoginRequest request) {
        SysUser user = findByUsername(request.getUsername().trim());

        if (user == null) {
            // 关键：用户不存在也走一次 BCrypt，避免响应时间泄露"账号是否存在"
            passwordEncoder.matches(request.getPassword(), DUMMY_BCRYPT_HASH);
            log.warn("登录失败（用户不存在） username={}", request.getUsername());
            throw new BizException(Result.CODE_BAD_REQUEST, "用户名或密码错误");
        }
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            log.warn("登录失败（密码错误） username={}", request.getUsername());
            throw new BizException(Result.CODE_BAD_REQUEST, "用户名或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BizException(Result.CODE_FORBIDDEN, "账号已被禁用，请联系管理员");
        }

        log.info("用户登录成功 userId={} username={}", user.getId(), user.getUsername());
        return issueTokens(user);
    }

    // ------------------------------------------------------------------ 刷新

    @Override
    public LoginVO refresh(String refreshToken) {
        Claims claims = tokenProvider.parseAndRequireType(refreshToken, JwtTokenProvider.TYPE_REFRESH);
        if (claims == null) {
            throw new BizException(Result.CODE_UNAUTHORIZED, "refreshToken 无效或已过期，请重新登录");
        }

        Long userId = tokenProvider.getUserId(claims);
        String jti = tokenProvider.getJti(claims);
        if (userId == null || jti == null) {
            throw new BizException(Result.CODE_UNAUTHORIZED, "refreshToken 无效，请重新登录");
        }

        // 原子抢占：DEL 返回 true 表示本请求独占了这次轮换机会。
        // 并发下只有一个请求能拿到 true，其余直接判失效，refresh token 无法双花。
        Boolean consumed = stringRedisTemplate.delete(refreshKey(userId, jti));
        if (!Boolean.TRUE.equals(consumed)) {
            log.warn("refreshToken 已被使用或已登出 userId={} jti={}", userId, jti);
            throw new BizException(Result.CODE_UNAUTHORIZED, "refreshToken 已失效，请重新登录");
        }

        SysUser user = sysUserService.getById(userId);
        if (user == null || (user.getStatus() != null && user.getStatus() == 0)) {
            throw new BizException(Result.CODE_UNAUTHORIZED, "用户不存在或已被禁用");
        }

        log.info("刷新令牌成功 userId={} 已消费 jti={}", userId, jti);
        return issueTokens(user);
    }

    // ------------------------------------------------------------------ 登出

    @Override
    public void logout(Long userId, String refreshToken) {
        if (!StringUtils.hasText(refreshToken)) {
            // 前端没传 refreshToken 时按"全部端下线"处理，简单且安全
            int removed = deleteAllRefreshTokens(userId);
            log.info("登出（全端） userId={} 清理 refreshToken {} 条", userId, removed);
            return;
        }
        Claims claims = tokenProvider.parseAndRequireType(refreshToken, JwtTokenProvider.TYPE_REFRESH);
        if (claims != null) {
            String jti = tokenProvider.getJti(claims);
            if (jti != null) {
                stringRedisTemplate.delete(refreshKey(userId, jti));
            }
        }
        log.info("登出 userId={}", userId);
    }

    /**
     * 删除该用户所有 refresh token（全端下线）。
     * <p>用 {@code SCAN} 而不是 {@code KEYS}：{@code KEYS} 会阻塞 Redis 单线程，
     * key 多的时候直接拖垮整个实例；{@code SCAN} 是游标式增量遍历，不阻塞。</p>
     *
     * @return 删除的 key 数量
     */
    private int deleteAllRefreshTokens(Long userId) {
        String pattern = jwtProperties.getRefreshKeyPrefix() + userId + ":*";
        int removed = 0;
        try (var cursor = stringRedisTemplate.scan(
                org.springframework.data.redis.core.ScanOptions.scanOptions().match(pattern).count(200).build())) {
            while (cursor.hasNext()) {
                stringRedisTemplate.delete(cursor.next());
                removed++;
            }
        } catch (Exception e) {
            log.warn("清理用户 refreshToken 失败 userId={}，降级为忽略", userId, e);
        }
        return removed;
    }

    // --------------------------------------------------------------- 微信登录

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginVO wxLogin(WxLoginRequest request) {
        String openid = resolveOpenid(request.getCode());

        SysUser user = findByOpenid(openid);

        if (user == null) {
            user = createWxUser(openid, request);
            log.info("微信用户首次登录，自动建号 userId={} openid={}", user.getId(), openid);
        } else if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BizException(Result.CODE_FORBIDDEN, "账号已被禁用，请联系管理员");
        } else {
            // 昵称/头像同步（微信侧可能已修改）
            boolean changed = false;
            if (StringUtils.hasText(request.getNickname()) && !request.getNickname().equals(user.getNickname())) {
                user.setNickname(request.getNickname());
                changed = true;
            }
            if (StringUtils.hasText(request.getAvatar()) && !request.getAvatar().equals(user.getAvatar())) {
                user.setAvatar(request.getAvatar());
                changed = true;
            }
            if (changed) {
                sysUserService.updateById(user);
            }
        }

        return issueTokens(user);
    }

    /**
     * 用 code 换 openid。
     *
     * <p>第 1 周还没有真实 AppID：{@code wx.mock-enabled=true} 时用 code 的 SHA-256
     * 派生一个稳定的假 openid，同一个 code 永远得到同一个 openid，
     * 这样"首次建号 / 二次登录"两条分支都能在没有微信后台的情况下验证。
     * 第 4 周拿真实 AppID 后把 mock-enabled 置为 false 即可切到真实接口。</p>
     */
    private String resolveOpenid(String code) {
        if (wxProperties.isMockEnabled()) {
            String mockOpenid = "mock_openid_" + sha256Hex(code).substring(0, 24);
            log.debug("wx.mock-enabled=true，使用 mock openid 走通流程");
            return mockOpenid;
        }

        // 真实链路：GET https://api.weixin.qq.com/sns/jscode2session
        JsonNode body;
        try {
            String raw = RestClient.create()
                    .get()
                    .uri(wxProperties.getCode2sessionUrl()
                                    + "?appid={appid}&secret={secret}&js_code={code}&grant_type=authorization_code",
                            wxProperties.getAppid(), wxProperties.getSecret(), code)
                    .retrieve()
                    .body(String.class);
            body = objectMapper.readTree(raw == null ? "{}" : raw);
        } catch (Exception e) {
            log.error("调用微信 code2session 失败", e);
            throw new BizException("微信登录失败：无法连接微信服务器，请稍后重试");
        }

        if (body.hasNonNull("errcode") && body.get("errcode").asInt() != 0) {
            log.warn("code2session 返回错误: {}", body);
            throw new BizException("微信登录失败：" + body.path("errmsg").asText("未知错误"));
        }
        String openid = body.path("openid").asText(null);
        if (!StringUtils.hasText(openid)) {
            throw new BizException("微信登录失败：未获取到 openid");
        }
        return openid;
    }

    /** 微信新用户建号：username 用 openid 派生（保证唯一且非空），不设可用密码 */
    private SysUser createWxUser(String openid, WxLoginRequest request) {
        SysUser user = new SysUser();
        user.setUsername("wx_" + openid.substring(Math.max(0, openid.length() - 12)));
        user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setNickname(StringUtils.hasText(request.getNickname()) ? request.getNickname() : "微信用户");
        user.setAvatar(request.getAvatar());
        user.setOpenid(openid);
        user.setStatus(1);
        try {
            sysUserService.save(user);
        } catch (DuplicateKeyException e) {
            // 极端并发：另一个请求已建号，直接查回来
            return findByOpenid(openid);
        }
        return user;
    }

    // ------------------------------------------------------------- 用户信息

    @Override
    public UserInfoVO currentUserInfo(Long userId) {
        SysUser user = sysUserService.getById(userId);
        if (user == null) {
            throw new BizException(Result.CODE_UNAUTHORIZED, "用户不存在");
        }
        return UserInfoVO.from(user);
    }

    // ------------------------------------------------------------------ 内部

    /** 签发一对令牌，并把 refresh token 的 jti 落到 Redis */
    private LoginVO issueTokens(SysUser user) {
        String accessToken = tokenProvider.createAccessToken(user.getId(), user.getUsername(), user.getNickname());

        // 一次生成同时拿到 token 与其 jti，避免为了取 jti 再解析一遍（多一次签名验签开销）
        JwtTokenProvider.IssuedToken refresh = tokenProvider.createRefreshToken(user.getId(), user.getUsername());
        stringRedisTemplate.opsForValue().set(
                refreshKey(user.getId(), refresh.jti()),
                String.valueOf(System.currentTimeMillis()),
                Duration.ofSeconds(jwtProperties.getRefreshExpireSeconds()));

        return LoginVO.builder()
                .accessToken(accessToken)
                .refreshToken(refresh.token())
                .tokenType("Bearer")
                .expiresIn(jwtProperties.getAccessExpireSeconds())
                .userInfo(UserInfoVO.from(user))
                .build();
    }

    private boolean existsByUsername(String username) {
        Long count = sysUserService.count(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, username));
        return count != null && count > 0;
    }

    private SysUser findByUsername(String username) {
        return sysUserService.getOne(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, username), false);
    }

    private SysUser findByOpenid(String openid) {
        return sysUserService.getOne(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getOpenid, openid), false);
    }

    private String refreshKey(Long userId, String jti) {
        return jwtProperties.getRefreshKeyPrefix() + userId + ":" + jti;
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
