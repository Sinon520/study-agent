package com.studyagent.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置项，对应 application.yml 中的 {@code jwt.*}。
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /** 开发环境默认密钥，与 application.yml 里的默认值保持一致 */
    public static final String DEV_DEFAULT_SECRET =
            "study-agent-jwt-secret-key-please-change-in-production-2025";

    /**
     * 签名密钥。
     * <p>密钥长度决定算法：jjwt 会按密钥字节长度自动选择 HS256/HS384/HS512。
     * 当前默认值 58 字节 → 自动使用 <b>HS384</b>；只要不小于 32 字节即安全。</p>
     * <p>生产环境务必用环境变量 {@code JWT_SECRET} 覆盖。</p>
     */
    private String secret = DEV_DEFAULT_SECRET;

    /** access token 有效期（秒），默认 2 小时 */
    private long accessExpireSeconds = 7200L;

    /** refresh token 有效期（秒），默认 7 天 */
    private long refreshExpireSeconds = 604800L;

    /** 签发者，便于多服务共用一个密钥时区分来源 */
    private String issuer = "study-agent";

    /** Redis 中 refresh token 的 key 前缀 */
    private String refreshKeyPrefix = "auth:refresh:";

    /**
     * 启动期校验：宁可启动失败，也不要带着弱密钥跑起来。
     * <p>用环境变量显式设置了 {@code JWT_SECRET} 却仍然等于开发默认值是配置错误；
     * 完全没设置则只告警（本地开发场景）。</p>
     */
    @PostConstruct
    public void validate() {
        int length = secret == null ? 0 : secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (length < 32) {
            // fail fast：HS 系列算法都是 HMAC-SHA，密钥不足 256 bit 时安全性无法保证
            throw new IllegalStateException(
                    "jwt.secret 长度不足 32 字节（当前 " + length + "），请通过环境变量 JWT_SECRET 配置更长的密钥");
        }
        boolean fromEnv = System.getenv("JWT_SECRET") != null;
        if (DEV_DEFAULT_SECRET.equals(secret)) {
            if (fromEnv) {
                throw new IllegalStateException(
                        "检测到环境变量 JWT_SECRET 与开发默认密钥相同，这是无效配置，请换成随机密钥");
            }
            log.warn("""
                    ============================================================
                     jwt.secret 正在使用开发默认密钥（{} 字节，算法 HS384）。
                     生产环境请设置环境变量 JWT_SECRET 覆盖，否则 token 可被伪造。
                    ============================================================""", length);
        }
    }
}
