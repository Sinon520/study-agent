package com.studyagent.model.vo;

import com.studyagent.entity.SysUser;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

/**
 * 用户信息返回体。
 * <p>刻意不含 password 字段：不要用 {@code @JsonIgnore} 去"补救"实体，
 * 而是从一开始就用 VO 隔离，这样 Swagger 文档里也不会泄露字段。</p>
 */
@Data
@Builder
@Schema(description = "用户信息")
public class UserInfoVO {

    @Schema(description = "用户 ID", example = "1")
    private Long id;

    @Schema(description = "登录账号", example = "demo")
    private String username;

    @Schema(description = "昵称", example = "演示同学")
    private String nickname;

    @Schema(description = "头像 URL")
    private String avatar;

    @Schema(description = "手机号")
    private String phone;

    @Schema(description = "备考类型", example = "考研")
    private String examType;

    @Schema(description = "状态：1 正常 0 禁用", example = "1")
    private Integer status;

    @Schema(description = "是否已绑定微信", example = "false")
    private Boolean wxBound;

    public static UserInfoVO from(SysUser user) {
        if (user == null) {
            return null;
        }
        return UserInfoVO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .phone(user.getPhone())
                .examType(user.getExamType())
                .status(user.getStatus())
                .wxBound(user.getOpenid() != null && !user.getOpenid().isBlank())
                .build();
    }
}
