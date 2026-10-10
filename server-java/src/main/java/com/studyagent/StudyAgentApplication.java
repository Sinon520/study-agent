package com.studyagent;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;

/**
 * 备考学习 Agent —— Java 业务主栈启动类。
 * <p>{@code @MapperScan} 只在这里声明一次（配置类中不再重复）。</p>
 */
@Slf4j
@SpringBootApplication
@MapperScan("com.studyagent.mapper")
public class StudyAgentApplication {

    public static void main(String[] args) {
        Environment env = SpringApplication.run(StudyAgentApplication.class, args).getEnvironment();

        String port = env.getProperty("server.port", "8080");
        String contextPath = env.getProperty("server.servlet.context-path", "");
        String appName = env.getProperty("spring.application.name", "server-java");

        log.info("""
                        
                        ----------------------------------------------------------
                          \u2705 {} 启动完成！
                          \u2705 Profile(s)   : {}
                          \u2705 本地访问     : http://127.0.0.1:{}{}/health
                          \u2705 接口文档     : http://127.0.0.1:{}{}/swagger-ui.html
                          \u2705 OpenAPI JSON : http://127.0.0.1:{}{}/v3/api-docs
                          \u2705 日志文件     : ./logs/server-java.log
                        ----------------------------------------------------------""",
                appName,
                String.join(",", env.getActiveProfiles().length == 0 ? new String[]{"default"} : env.getActiveProfiles()),
                port, contextPath,
                port, contextPath,
                port, contextPath);
    }
}
