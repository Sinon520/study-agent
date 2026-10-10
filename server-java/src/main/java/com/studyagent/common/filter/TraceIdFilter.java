package com.studyagent.common.filter;

import com.studyagent.common.util.TraceIdConstants;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * traceId 全链路过滤器。
 * <p>优先级最高，保证后续所有 Filter / Interceptor / Controller / SQL 日志都能拿到 traceId。</p>
 * <ul>
 *   <li>优先读取请求头 {@code X-Trace-Id}（支持网关或前端透传）</li>
 *   <li>没有则生成 32 位无横线 UUID</li>
 *   <li>写入 MDC 与响应头</li>
 *   <li>finally 中清理 MDC，防止 Tomcat 线程池复用导致脏数据</li>
 * </ul>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String traceId = null;
        try {
            if (request instanceof HttpServletRequest httpRequest) {
                traceId = httpRequest.getHeader(TraceIdConstants.HEADER);
            }
            if (traceId == null || traceId.isBlank()) {
                traceId = UUID.randomUUID().toString().replace("-", "");
            }
            traceId = traceId.trim();

            MDC.put(TraceIdConstants.MDC_KEY, traceId);

            if (response instanceof HttpServletResponse httpResponse) {
                httpResponse.setHeader(TraceIdConstants.HEADER, traceId);
            }

            chain.doFilter(request, response);
        } finally {
            // 关键：必须在 finally 中移除，避免线程池复用时串号
            MDC.remove(TraceIdConstants.MDC_KEY);
        }
    }
}
