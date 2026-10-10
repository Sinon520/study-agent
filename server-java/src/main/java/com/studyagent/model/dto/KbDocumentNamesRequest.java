package com.studyagent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 按 ID 批量查询文档名请求（内部接口，Python 生成引用溯源时用）。
 */
@Data
@Schema(description = "批量查询文档名请求（内部）")
public class KbDocumentNamesRequest {

    @NotNull(message = "documentIds 不能为空")
    @Schema(description = "文档 ID 列表")
    private List<Long> documentIds;
}
