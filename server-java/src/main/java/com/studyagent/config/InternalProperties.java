package com.studyagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 内部接口配置，对应 application.yml 中的 {@code internal.*}。
 *
 * <p>与 Python 侧的 {@code INTERNAL_TOKEN} 必须一致，
 * 生产环境用环境变量注入，不进 Git。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "internal")
public class InternalProperties {

    /** 内部接口共享令牌 */
    private String token = "study-agent-internal";

    /** Python AI 服务基地址（Java 主动调用时使用） */
    private String pythonBaseUrl = "http://127.0.0.1:8000";

    /** 回调 Python 的超时（秒） */
    private int pythonTimeoutSeconds = 600;
}
