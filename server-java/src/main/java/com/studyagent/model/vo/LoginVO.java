package com.studyagent.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

/**
 * 登录/刷新成功后返回的令牌对。
 */
@Data
@Builder
@Schema(description = "登录令牌")
public class LoginVO {

    @Schema(description = "访问令牌，放进 Authorization: Bearer <token>")
    private String accessToken;

    @Schema(description = "刷新令牌，access 过期后用它换新的令牌对")
    private String refreshToken;

    @Schema(description = "令牌类型，固定 Bearer", example = "Bearer")
    private String tokenType;

    @Schema(description = "access token 剩余有效期（秒）", example = "7200")
    private Long expiresIn;

    @Schema(description = "用户信息")
    private UserInfoVO userInfo;
}
