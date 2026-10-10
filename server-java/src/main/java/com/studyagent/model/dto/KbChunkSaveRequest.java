package com.studyagent.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * Python 侧回写切片元数据的请求（内部接口）。
 *
 * <p>为什么由 Python 回调 Java 而不是直连 MySQL：保持"单一写入口"。
 * 权限校验、事务、审计、traceId 全部只在 Java 实现一次。</p>
 */
@Data
@Schema(description = "切片回写请求（内部）")
public class KbChunkSaveRequest {

    @NotNull(message = "documentId 不能为空")
    @Schema(description = "文档 ID")
    private Long documentId;

    @Schema(description = "切片列表")
    private List<Item> chunks;

    @Data
    @Schema(description = "单个切片")
    public static class Item {

        @NotNull(message = "chunkIndex 不能为空")
        @Schema(description = "切片序号，从 0 开始")
        private Integer chunkIndex;

        @NotBlank(message = "切片内容不能为空")
        @Schema(description = "切片文本")
        private String content;

        @Schema(description = "来源页码")
        private Integer pageNo;

        @NotBlank(message = "vectorId 不能为空")
        @Schema(description = "Milvus 主键")
        private String vectorId;
    }
}
