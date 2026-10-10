package com.studyagent.model.vo;

import com.studyagent.entity.KbDocument;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库文档视图。
 */
@Data
@Schema(description = "知识库文档")
public class KbDocumentVO {

    @Schema(description = "文档 ID")
    private Long id;

    @Schema(description = "原始文件名")
    private String fileName;

    @Schema(description = "文件类型")
    private String fileType;

    @Schema(description = "文件大小（字节）")
    private Long fileSize;

    @Schema(description = "文件大小（可读）", example = "2.3 MB")
    private String fileSizeText;

    @Schema(description = "解析状态：待解析/解析中/完成/失败")
    private String parseStatus;

    @Schema(description = "切片数量")
    private Integer chunkCount;

    @Schema(description = "解析失败原因")
    private String errorMsg;

    @Schema(description = "上传时间")
    private LocalDateTime uploadTime;

    public static KbDocumentVO from(KbDocument document) {
        KbDocumentVO vo = new KbDocumentVO();
        vo.setId(document.getId());
        vo.setFileName(document.getFileName());
        vo.setFileType(document.getFileType());
        vo.setFileSize(document.getFileSize());
        vo.setFileSizeText(humanSize(document.getFileSize()));
        vo.setParseStatus(document.getParseStatus());
        vo.setChunkCount(document.getChunkCount());
        vo.setErrorMsg(document.getErrorMsg());
        vo.setUploadTime(document.getUploadTime());
        return vo;
    }

    private static String humanSize(Long bytes) {
        if (bytes == null || bytes <= 0) {
            return "-";
        }
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB"};
        int unitIndex = 0;
        while (value >= 1024 && unitIndex < units.length - 1) {
            value /= 1024;
            unitIndex++;
        }
        return String.format("%.1f %s", value, units[unitIndex]);
    }
}
