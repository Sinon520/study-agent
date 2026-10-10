package com.studyagent.security;

import com.studyagent.config.JwtProperties;
import com.studyagent.common.util.TraceIdConstants;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 签发与解析工具。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>access token 与 refresh token 用同一密钥签发，靠 {@code type} claim 区分用途，
 *       避免 refresh token 被直接当作 access token 使用。</li>
 *   <li>每个 token 带唯一 {@code jti}（UUID），refresh token 的 jti 是 Redis 中的 key 后缀，
 *       这样同一用户的多次登录互不影响，也支持单点失效。</li>
 *   <li>把 {@code traceId} 写进 claim：token 换服务排查问题时能反查到签发时的链路。</li>
 * </ul>
 */
@Slf4j
@Component
public class JwtTokenProvider {

    /** token 用途：访问令牌 */
    public static final String TYPE_ACCESS = "access";
    /** token 用途：刷新令牌 */
    public static final String TYPE_REFRESH = "refresh";

    private static final String CLAIM_TYPE = "type";
    private static final String CLAIM_USERNAME = "username";
    private static final String CLAIM_NICKNAME = "nickname";

    private final JwtProperties properties;
    private final SecretKey secretKey;

    public JwtTokenProvider(JwtProperties properties) {
        this.properties = properties;
        byte[] keyBytes = properties.getSecret().getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "jwt.secret 长度不足 32 字节，HS256 要求至少 256 bit，请在 application.yml 或环境变量中配置更长的密钥");
        }
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /** 签发结果：token 与其 jti 一起返回，避免调用方为了取 jti 再解析一遍 */
    public record IssuedToken(String token, String jti) {
    }

    /** 签发 access token */
    public String createAccessToken(Long userId, String username, String nickname) {
        return createToken(userId, username, nickname, TYPE_ACCESS, properties.getAccessExpireSeconds()).token();
    }

    /**
     * 签发 refresh token。
     * <p>返回 token 与 jti：jti 是 Redis 中记录这次登录的 key 后缀，
     * 调用方必须拿到它才能落库/后续删除。</p>
     */
    public IssuedToken createRefreshToken(Long userId, String username) {
        return createToken(userId, username, null, TYPE_REFRESH, properties.getRefreshExpireSeconds());
    }

    private IssuedToken createToken(Long userId, String username, String nickname, String type, long expireSeconds) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + expireSeconds * 1000L);
        String jti = UUID.randomUUID().toString().replace("-", "");

        String token = Jwts.builder()
                .id(jti)
                .subject(String.valueOf(userId))
                .issuer(properties.getIssuer())
                .claim(CLAIM_TYPE, type)
                .claim(CLAIM_USERNAME, username)
                .claim(CLAIM_NICKNAME, nickname)
                .claim(TraceIdConstants.MDC_KEY, safeTraceId())
                .issuedAt(now)
                .expiration(expiration)
                .signWith(secretKey)
                .compact();

        return new IssuedToken(token, jti);
    }

    /**
     * 解析并校验 token。
     *
     * @param token 待解析的 token
     * @return claims；token 非法、过期、签名不匹配时返回 {@code null}（由调用方决定如何处理）
     */
    public Claims parse(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .requireIssuer(properties.getIssuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            // 过期与签名错误都走这里；只记 debug，避免被恶意请求刷日志
            log.debug("JWT 校验失败: {}", e.getMessage());
            return null;
        }
    }

    /** 解析并断言 token 类型，类型不符返回 null */
    public Claims parseAndRequireType(String token, String expectedType) {
        Claims claims = parse(token);
        if (claims == null) {
            return null;
        }
        if (!expectedType.equals(claims.get(CLAIM_TYPE, String.class))) {
            log.debug("JWT 类型不符，期望 {} 实际 {}", expectedType, claims.get(CLAIM_TYPE, String.class));
            return null;
        }
        return claims;
    }

    /**
     * 从 claims 取用户 ID。
     * <p>返回 {@code null} 而不是抛异常：{@code sub} 被构造成非数字时（换密钥、多环境混用等场景）
     * 只应该导致"认证失败"，不应该从"解析返回 null"的约定外逃成 500。</p>
     */
    public Long getUserId(Claims claims) {
        try {
            return Long.valueOf(claims.getSubject());
        } catch (NumberFormatException | NullPointerException e) {
            log.debug("JWT subject 不是合法用户 ID: {}", claims.getSubject());
            return null;
        }
    }

    /** 从 claims 取用户名 */
    public String getUsername(Claims claims) {
        return claims.get(CLAIM_USERNAME, String.class);
    }

    /** 从 claims 取 jti */
    public String getJti(Claims claims) {
        return claims.getId();
    }

    public JwtProperties getProperties() {
        return properties;
    }

    private String safeTraceId() {
        try {
            return MDC.get(TraceIdConstants.MDC_KEY);
        } catch (Exception e) {
            return null;
        }
    }
}
