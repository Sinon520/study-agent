package com.studyagent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 微信小程序登录请求。
 * <p>前端 {@code uni.login()} / {@code wx.login()} 拿到临时 code 后传上来。</p>
 */
@Data
@Schema(description = "微信小程序登录请求")
public class WxLoginRequest {

    @NotBlank(message = "code 不能为空")
    @Schema(description = "wx.login 返回的临时登录凭证", example = "081Xyz...")
    private String code;

    @Schema(description = "微信昵称（前端可选上报，用于首次建号）", example = "微信用户")
    private String nickname;

    @Schema(description = "微信头像 URL", example = "https://thirdwx.qlogo.cn/...")
    private String avatar;
}
