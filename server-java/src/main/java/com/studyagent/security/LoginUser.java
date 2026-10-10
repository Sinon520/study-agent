package com.studyagent.security;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 认证通过后放进 Spring Security 上下文的登录用户信息。
 * <p>刻意不实现 {@code UserDetails}：本项目不用 Spring Security 的
 * 角色/权限模型，只借它的过滤器链做认证，保持简单。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginUser implements Serializable {

    private Long userId;

    private String username;

    private String nickname;
}
