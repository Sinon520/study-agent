package com.studyagent.service;

import com.studyagent.model.dto.LoginRequest;
import com.studyagent.model.dto.RegisterRequest;
import com.studyagent.model.dto.WxLoginRequest;
import com.studyagent.model.vo.LoginVO;
import com.studyagent.model.vo.UserInfoVO;

/**
 * 认证服务。
 */
public interface AuthService {

    /**
     * 注册账号。
     *
     * @return 新用户信息（不直接签发 token，让前端注册成功后显式跳登录，
     *         避免"注册即登录"掩盖密码错误的问题）
     */
    UserInfoVO register(RegisterRequest request);

    /** 账号密码登录，签发 access + refresh token */
    LoginVO login(LoginRequest request);

    /**
     * 用 refresh token 换一套新令牌。
     * <p>采用<b>轮换策略</b>：旧 refresh token 立即失效，防止被重放。</p>
     */
    LoginVO refresh(String refreshToken);

    /** 退出登录：删除 Redis 中的 refresh token 记录 */
    void logout(Long userId, String refreshToken);

    /** 微信小程序登录：code → openid → 查/建用户 → 签发 token */
    LoginVO wxLogin(WxLoginRequest request);

    /** 查询当前用户信息 */
    UserInfoVO currentUserInfo(Long userId);
}
