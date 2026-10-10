package com.studyagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 知识库文档切片表
 */
@Data
@TableName("kb_chunk")
@Schema(description = "知识库文档切片")
public class KbChunk implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "所属文档")
    private Long documentId;

    @Schema(description = "所属用户ID（冗余，检索时免 join 过滤）")
    private Long userId;

    @Schema(description = "片序号，从 0 开始")
    private Integer chunkIndex;

    @Schema(description = "切片文本")
    private String content;

    @Schema(description = "来源页码，来自解析来源")
    private Integer pageNo;

    @Schema(description = "Milvus 主键")
    private String vectorId;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
