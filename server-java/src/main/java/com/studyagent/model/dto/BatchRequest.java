package com.studyagent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 批量操作请求（批量删除 / 批量标记掌握度）。
 */
@Data
@Schema(description = "批量操作请求")
public class BatchRequest {

    @NotEmpty(message = "ID 列表不能为空")
    @Schema(description = "目标 ID 列表")
    private List<Long> ids;

    @Schema(description = "批量标记掌握度时使用：未掌握/复习中/已掌握", example = "已掌握")
    private String mastery;
}
