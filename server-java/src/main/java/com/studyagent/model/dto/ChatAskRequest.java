package com.studyagent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 问答请求（前端 → Java）。
 *
 * <p>注意**没有 userId 字段**：用户身份一律取自 JWT，
 * 绝不允许前端指定"我要以谁的资料问答"。</p>
 */
@Data
@Schema(description = "问答请求")
public class ChatAskRequest {

    @NotBlank(message = "问题不能为空")
    @Size(max = 2000, message = "问题最长 2000 个字符")
    @Schema(description = "用户问题", example = "定积分的几何意义是什么？")
    private String question;

    @Schema(description = "会话 ID；不传表示新建会话")
    private Long sessionId;

    @Schema(description = "限定只在这些文档里检索（可选）")
    private List<Long> documentIds;
}
