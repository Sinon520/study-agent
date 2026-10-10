package com.studyagent.config;

import com.studyagent.security.InternalTokenFilter;
import com.studyagent.security.JwtAuthenticationFilter;
import com.studyagent.security.RestAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 配置。
 *
 * <p>过滤器链顺序：{@code TraceIdFilter}（Servlet 层，优先级最高，先生成 traceId）
 * → {@code SecurityFilterChain}（内含 {@link InternalTokenFilter} 与
 * {@link JwtAuthenticationFilter}）→ Controller。</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final InternalTokenFilter internalTokenFilter;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;

    /**
     * 白名单：这些路径无需认证。
     * <p>{@code /error} 必须放行，否则全局异常处理的结果会被 401 掩盖。</p>
     *
     * <p>{@code /internal/**} 也在白名单里，但**不是裸奔**：
     * 它由 {@link InternalTokenFilter} 单独校验共享令牌 + 来源 IP。
     * 之所以不放进 Spring Security 的认证体系，是因为调用方是 Python 服务，
     * 它没有用户 JWT，硬套 JWT 只会让两边都变复杂。</p>
     */
    public static final String[] WHITE_LIST = {
            "/auth/**",
            "/internal/**",
            // 挂在业务命名空间下的内部接口：调用方是 Python 规划 Agent（无用户 JWT），
            // 由 InternalTokenFilter 校验 X-Internal-Token + 来源 IP。
            // 注意必须在这里放行 Spring Security，否则会被 JWT 链路直接 401 拦掉，
            // 根本走不到 InternalTokenFilter（过滤器在授权判断之前，但授权失败会先返回）。
            "/plan/agent-create",
            "/health",
            "/error",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/doc.html",
            "/webjars/**",
            "/actuator/health",
            "/actuator/health/**",
            "/favicon.ico"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 前后端分离 + JWT，无需 CSRF
                .csrf(csrf -> csrf.disable())
                // 不使用 Session
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 关闭默认登录/登出页与 Basic 认证（后续用 JWT 过滤器替换）
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .cors(Customizer.withDefaults())
                // 未认证时返回统一返回体，而不是默认的空 401
                .exceptionHandling(handler -> handler.authenticationEntryPoint(restAuthenticationEntryPoint))
                .authorizeHttpRequests(auth -> auth
                        // 预检请求直接放行
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(WHITE_LIST).permitAll()
                        .anyRequest().authenticated()
                )
                // 内部接口令牌校验要排在 JWT 之前：内部调用本来就没有 JWT
                .addFilterBefore(internalTokenFilter, UsernamePasswordAuthenticationFilter.class)
                // JWT 过滤器插在用户名密码认证过滤器之前
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 密码编码器：BCrypt。
     * <p>注意 {@code $2a$10$...} 与 {@code $2b$...} 前缀的密文 Spring Security 的
     * {@code BCryptPasswordEncoder.matches} 都能校验通过。</p>
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
