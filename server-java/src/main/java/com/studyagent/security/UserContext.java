package com.studyagent.security;

import com.studyagent.common.Result;
import com.studyagent.common.exception.BizException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前登录用户上下文工具。
 *
 * <p>业务代码里不该到处写 {@code SecurityContextHolder.getContext()...}，
 * 统一从这里取，后续若换成网关透传用户信息，只改这一个类。</p>
 */
public final class UserContext {

    private UserContext() {
    }

    /** 取当前登录用户，未登录抛 401 业务异常 */
    public static LoginUser current() {
        LoginUser user = currentOrNull();
        if (user == null) {
            throw new BizException(Result.CODE_UNAUTHORIZED, "未登录或登录已过期");
        }
        return user;
    }

    /** 取当前登录用户，未登录返回 null（供可选登录场景使用） */
    public static LoginUser currentOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof LoginUser loginUser)) {
            return null;
        }
        return loginUser;
    }

    /** 取当前用户 ID，未登录抛 401 */
    public static Long userId() {
        return current().getUserId();
    }

    /**
     * 取当前用户 ID，未登录返回 {@code null}。
     * <p>给「匿名可访问但登录后行为不同」的接口用。</p>
     */
    public static Long userIdOrNull() {
        LoginUser user = currentOrNull();
        return user == null ? null : user.getUserId();
    }
}
