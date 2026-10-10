package com.studyagent.controller;

import com.studyagent.common.Result;
import com.studyagent.model.dto.LoginRequest;
import com.studyagent.model.dto.RefreshTokenRequest;
import com.studyagent.model.dto.RegisterRequest;
import com.studyagent.model.dto.WxLoginRequest;
import com.studyagent.model.vo.LoginVO;
import com.studyagent.model.vo.UserInfoVO;
import com.studyagent.security.LoginUser;
import com.studyagent.security.UserContext;
import com.studyagent.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。
 * <p>{@code /auth/**} 全部在白名单里，除 {@code /auth/me} 与 {@code /auth/logout}
 * 需要携带 token 外，其余无需认证。</p>
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "01-认证", description = "注册、登录、刷新令牌、小程序登录")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    @Operation(summary = "账号注册", description = "用户名唯一，密码 BCrypt 加密后入库")
    public Result<UserInfoVO> register(@Valid @RequestBody RegisterRequest request) {
        return Result.ok("注册成功", authService.register(request));
    }

    @PostMapping("/login")
    @Operation(summary = "账号密码登录", description = "返回 accessToken(2h) + refreshToken(7d)，refreshToken 落 Redis")
    public Result<LoginVO> login(@Valid @RequestBody LoginRequest request) {
        return Result.ok("登录成功", authService.login(request));
    }

    @PostMapping("/refresh")
    @Operation(summary = "刷新令牌", description = "轮换策略：旧 refreshToken 立即失效")
    public Result<LoginVO> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return Result.ok("刷新成功", authService.refresh(request.getRefreshToken()));
    }

    @PostMapping("/logout")
    @Operation(summary = "退出登录", description = "删除 Redis 中的 refreshToken；不传 refreshToken 则全端下线",
            security = @SecurityRequirement(name = "bearerAuth"))
    public Result<Void> logout(@RequestBody(required = false) RefreshTokenRequest request) {
        LoginUser loginUser = UserContext.current();
        authService.logout(loginUser.getUserId(), request == null ? null : request.getRefreshToken());
        return Result.ok("退出成功", null);
    }

    @PostMapping("/wx-login")
    @Operation(summary = "微信小程序登录",
            description = "前端传 wx.login 拿到的 code；后端换 openid 后查/建用户并签发 JWT。"
                    + "第 1 周无真实 AppID 时由 wx.mock-enabled=true 走本地 mock。")
    public Result<LoginVO> wxLogin(@Valid @RequestBody WxLoginRequest request) {
        return Result.ok("登录成功", authService.wxLogin(request));
    }

    @GetMapping("/me")
    @Operation(summary = "获取当前登录用户信息", security = @SecurityRequirement(name = "bearerAuth"))
    public Result<UserInfoVO> me() {
        return Result.ok(authService.currentUserInfo(UserContext.userId()));
    }
}
