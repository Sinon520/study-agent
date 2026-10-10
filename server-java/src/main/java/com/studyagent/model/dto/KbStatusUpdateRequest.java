package com.studyagent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 文档解析状态更新请求（内部接口，Python 回调）。
 */
@Data
@Schema(description = "文档状态更新请求（内部）")
public class KbStatusUpdateRequest {

    @NotBlank(message = "parseStatus 不能为空")
    @Schema(description = "状态：待解析/解析中/完成/失败", example = "完成")
    private String parseStatus;

    @Schema(description = "切片数量，完成时回填")
    private Integer chunkCount;

    @Schema(description = "失败原因")
    private String errorMsg;
}
