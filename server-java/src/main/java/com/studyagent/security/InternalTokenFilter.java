package com.studyagent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.Result;
import com.studyagent.config.InternalProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 内部接口的令牌校验过滤器。
 *
 * <h3>为什么需要它</h3>
 * Python 服务需要写库时一律回调 Java 的 `/internal/**` 接口（保持单一写入口）。
 * 这些接口**不能走 JWT**（Python 没有用户令牌），但也**绝不能裸奔**：
 * 一旦能被外部直接调用，攻击者就能绕过所有权限校验往任意用户库里写数据。
 *
 * <p>因此做两层保护，缺一不可：</p>
 * <ol>
 *   <li><b>共享令牌</b>：请求头 {@code X-Internal-Token} 必须与配置一致
 *       （两边都从环境变量读，不进 Git）；</li>
 *   <li><b>来源限制</b>：只接受回环地址与本机内网网段，即使令牌泄露，
 *       外网也打不进来（配合部署时不暴露 8080 到公网）。</li>
 * </ol>
 *
 * <p>只作用于 {@code /internal/**}，其它接口完全不受影响。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InternalTokenFilter extends OncePerRequestFilter {

    /** 内部接口前缀 */
    public static final String INTERNAL_PREFIX = "/internal/";

    /**
     * 少数挂在业务命名空间下的内部接口。
     *
     * <p>{@code /plan/agent-create} 按执行计划的约定放在 /plan 下，
     * 但它的调用方是 Python 规划 Agent（没有用户 JWT），
     * 所以也要走内部令牌校验。</p>
     */
    public static final String[] EXTRA_INTERNAL_PATHS = {
            "/plan/agent-create"
    };

    /** 令牌请求头 */
    public static final String TOKEN_HEADER = "X-Internal-Token";

    private final InternalProperties internalProperties;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 只过滤内部接口，其余请求交给 Spring Security 的 JWT 链路
        String uri = request.getRequestURI();
        if (uri.startsWith(INTERNAL_PREFIX)) {
            return false;
        }
        for (String extra : EXTRA_INTERNAL_PATHS) {
            if (uri.equals(extra)) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String remoteAddr = resolveClientIp(request);

        if (!isTrustedSource(remoteAddr)) {
            log.warn("内部接口拒绝非内网来源: {} {} from {}", request.getMethod(), request.getRequestURI(), remoteAddr);
            reject(response, "内部接口仅允许内网访问");
            return;
        }

        String token = request.getHeader(TOKEN_HEADER);
        if (!StringUtils.hasText(token) || !constantTimeEquals(token, internalProperties.getToken())) {
            log.warn("内部接口令牌校验失败: {} {} from {}", request.getMethod(), request.getRequestURI(), remoteAddr);
            reject(response, "内部接口令牌无效");
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * 取客户端 IP。
     * <p>优先读代理头是为了部署在 Nginx 后面时也能正确判断来源；
     * 但注意这些头可伪造，所以真正的安全依赖"8080 不对公网开放"+ 令牌。</p>
     */
    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp)) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    /** 只信任回环与本机内网网段 */
    private boolean isTrustedSource(String ip) {
        if (!StringUtils.hasText(ip)) {
            return false;
        }
        if ("127.0.0.1".equals(ip) || "::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) {
            return true;
        }
        // IPv6 映射的 IPv4（Windows 上取到的可能是 ::ffff:127.0.0.1）
        if (ip.startsWith("::ffff:")) {
            ip = ip.substring(7);
        }
        return ip.startsWith("10.")
                || ip.startsWith("192.168.")
                // 172.16.0.0 ~ 172.31.255.255
                || ip.matches("^172\\.(1[6-9]|2\\d|3[01])\\..*");
    }

    /** 定长比较，避免令牌校验被时序侧信道推断 */
    private boolean constantTimeEquals(String provided, String expected) {
        if (expected == null) {
            return false;
        }
        byte[] a = provided.getBytes(StandardCharsets.UTF_8);
        byte[] b = expected.getBytes(StandardCharsets.UTF_8);
        if (a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
                objectMapper.writeValueAsString(Result.fail(Result.CODE_FORBIDDEN, message)));
    }
}
