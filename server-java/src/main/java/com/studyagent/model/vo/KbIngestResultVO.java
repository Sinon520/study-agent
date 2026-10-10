package com.studyagent.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 文档入库结果（解析 Python /rag/ingest 的响应）。
 */
@Data
@Schema(description = "文档入库结果")
public class KbIngestResultVO {

    @Schema(description = "文档 ID")
    private Long documentId;

    @Schema(description = "解析出的页/逻辑段数")
    private Integer pageCount;

    @Schema(description = "分块数")
    private Integer chunkCount;

    @Schema(description = "写入 Milvus 的向量数")
    private Integer vectorCount;

    @Schema(description = "分块大小")
    private Integer chunkSize;

    @Schema(description = "分块重叠")
    private Integer chunkOverlap;

    @Schema(description = "向量化耗时（秒）")
    private Double embedSeconds;

    @Schema(description = "全流程耗时（秒）")
    private Double totalSeconds;
}
