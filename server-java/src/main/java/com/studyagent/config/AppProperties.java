package com.studyagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 应用自定义配置，对应 application.yml 中的 {@code app.*}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** 文件上传落盘根目录（知识库文档放在其下的 kb/<userId>/ 子目录里） */
    private String uploadDir = "./uploads";

    /** Python AI 服务基地址（与 internal.python-base-url 保持同源，便于本地调试二选一） */
    private String pythonBaseUrl = "http://127.0.0.1:8000";
}
