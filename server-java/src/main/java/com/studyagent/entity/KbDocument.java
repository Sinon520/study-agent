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
 * 知识库文档表
 */
@Data
@TableName("kb_document")
@Schema(description = "知识库文档")
public class KbDocument implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "所属用户")
    private Long userId;

    @Schema(description = "原始文件名")
    private String fileName;

    @Schema(description = "本地存储路径")
    private String filePath;

    @Schema(description = "文件类型：pdf/docx/txt")
    private String fileType;

    @Schema(description = "文件大小（字节）")
    private Long fileSize;

    @Schema(description = "状态：待解析/解析中/完成/失败")
    private String parseStatus;

    @Schema(description = "切分后切片数量")
    private Integer chunkCount;

    @Schema(description = "解析失败原因")
    private String errorMsg;

    @Schema(description = "上传时间")
    private LocalDateTime uploadTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
