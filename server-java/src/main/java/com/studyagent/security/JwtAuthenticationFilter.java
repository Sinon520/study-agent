package com.studyagent.security;

import com.studyagent.config.JwtProperties;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 认证过滤器。
 *
 * <p>流程：读 {@code Authorization: Bearer <token>} → 校验签名与过期 →
 * 断言是 access token（refresh token 不能当访问凭证用）→
 * 构造 {@link LoginUser} 放进 {@link SecurityContextHolder}。</p>
 *
 * <p>注意：这里<b>不</b>直接拒绝非法 token，而是放行给后面的
 * {@code authorizeHttpRequests} + {@link RestAuthenticationEntryPoint} 处理，
 * 这样可以保留 Spring Security 的标准 401 语义。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "Authorization";
    public static final String TOKEN_PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String token = resolveToken(request);
        // 已认证则不重复解析（例如内部转发场景）
        if (StringUtils.hasText(token) && SecurityContextHolder.getContext().getAuthentication() == null) {
            Claims claims = tokenProvider.parseAndRequireType(token, JwtTokenProvider.TYPE_ACCESS);
            Long userId = claims == null ? null : tokenProvider.getUserId(claims);
            if (userId != null) {
                LoginUser loginUser = new LoginUser(
                        userId,
                        tokenProvider.getUsername(claims),
                        claims.get("nickname", String.class));

                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        loginUser, null, AuthorityUtils.NO_AUTHORITIES);
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);

                // 把用户 ID 也塞进 MDC，日志里能直接看到"谁在调"
                MDC.put("userId", String.valueOf(loginUser.getUserId()));
            }
        }

        try {
            chain.doFilter(request, response);
        } finally {
            // 单次请求结束即清理，避免线程复用污染
            MDC.remove("userId");
        }
    }

    /** 从请求头提取 token，兼容不带 Bearer 前缀的写法（便于 Postman 调试） */
    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (!StringUtils.hasText(header)) {
            return null;
        }
        if (header.startsWith(TOKEN_PREFIX)) {
            return header.substring(TOKEN_PREFIX.length()).trim();
        }
        return header.trim();
    }
}
