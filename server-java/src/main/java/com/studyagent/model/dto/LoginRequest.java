package com.studyagent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 账号密码登录请求。
 *
 * <p>长度上限不是形式主义：BCrypt 每字节都要参与运算，不限制长度的话
 * 攻击者可以提交 1MB 的密码，让服务端每次登录都做一次昂贵的哈希，
 * 用极低的成本放大成 CPU 耗尽（DoS）。</p>
 */
@Data
@Schema(description = "账号密码登录请求")
public class LoginRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(max = 32, message = "用户名长度不能超过 32")
    @Schema(description = "登录账号", example = "demo")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 64, message = "密码长度需在 6~64 之间")
    @Schema(description = "登录密码", example = "123456")
    private String password;
}
