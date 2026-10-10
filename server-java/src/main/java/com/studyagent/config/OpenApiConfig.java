package com.studyagent.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger / OpenAPI 配置（springdoc-openapi，替代已停更的 springfox）。
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI studyAgentOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("备考学习 Agent 服务端 API")
                        .version("1.0.0")
                        .description("""
                                备考学习 Agent 双栈架构的 Java 业务主栈 API 文档。

                                **双栈架构说明**
                                - `server-java`（本服务，Spring Boot 3 + MyBatis-Plus + MySQL + Redis + RocketMQ）
                                  负责：用户认证、学习计划、计划任务、错题本、学习记录、复盘报告、站内通知、
                                  知识库文档管理、问答会话与消息的持久化。
                                - `server-ai`（Python FastAPI + LangChain + Milvus）
                                  负责：文档解析与向量化、RAG 检索、大模型问答与推理、报告内容生成。

                                两者通过 HTTP（Java -> Python）与 RocketMQ（异步事件）协作。

                                **统一返回体**：`{ "code": 200, "msg": "success", "data": {...}, "traceId": "32位" }`。
                                HTTP 状态码恒为 200，业务状态看 `code`。

                                **链路追踪**：每个请求都会生成/透传 `X-Trace-Id`，响应头回写，
                                日志中以 `[traceId]` 输出，便于全链路排查。
                                """)
                        .contact(new Contact().name("study-agent"))
                        .license(new License().name("Apache 2.0")))
                // 预置 JWT 鉴权入口（第 5~6 天接入 JwtAuthenticationFilter 后即可直接使用）
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME_NAME,
                        new SecurityScheme()
                                .name(SECURITY_SCHEME_NAME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("请求头格式：Authorization: Bearer {accessToken}")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME));
    }

    /**
     * 关键：显式扫描 {@code com.studyagent.controller} 包。
     * <p>分组名指定为 {@code all}，可通过 {@code /v3/api-docs/all} 访问，
     * 而不指定 group 时默认地址 {@code /v3/api-docs} 依然可用。</p>
     */
    @Bean
    public GroupedOpenApi studyAgentApi() {
        return GroupedOpenApi.builder()
                .group("all")
                .displayName("全部接口")
                .packagesToScan("com.studyagent.controller")
                .pathsToMatch("/**")
                .build();
    }
}
