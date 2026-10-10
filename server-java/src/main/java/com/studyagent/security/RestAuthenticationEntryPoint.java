package com.studyagent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 未认证入口：把 Spring Security 默认的 302/403 换成统一返回体。
 *
 * <p>HTTP 状态码仍然返回 401，让前端的 axios 响应拦截器能凭状态码直接跳登录页；
 * body 里带上 {@code Result} 结构，方便直接展示 msg。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        log.debug("未认证访问被拦截: {} {}, 原因: {}",
                request.getMethod(), request.getRequestURI(), authException.getMessage());

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        Result<Void> body = Result.fail(Result.CODE_UNAUTHORIZED, "未登录或登录已过期，请重新登录");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
