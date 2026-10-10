package com.studyagent.controller;

import com.studyagent.common.Result;
import com.studyagent.model.dto.PageQuery;
import com.studyagent.model.vo.KbDocumentVO;
import com.studyagent.model.vo.PageResult;
import com.studyagent.security.UserContext;
import com.studyagent.service.KnowledgeBaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库接口（文档上传与管理）。
 *
 * <p>链路：前端上传 → Java 落盘 + 写 kb_document（状态=待解析）→ 立即返回
 * → 异步调 Python {@code /rag/ingest} 解析入库 → 回写切片与状态。</p>
 */
@Slf4j
@RestController
@RequestMapping("/kb")
@RequiredArgsConstructor
@Tag(name = "07-知识库", description = "资料上传、解析状态、切片管理")
@SecurityRequirement(name = "bearerAuth")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    @GetMapping("/page")
    @Operation(summary = "分页查询我的资料", description = "可按解析状态过滤")
    public Result<PageResult<KbDocumentVO>> page(
            PageQuery query,
            @Parameter(description = "解析状态：待解析/解析中/完成/失败")
            @RequestParam(required = false) String parseStatus) {
        return Result.ok(knowledgeBaseService.pageDocuments(UserContext.userId(), query, parseStatus));
    }

    @GetMapping("/{documentId}")
    @Operation(summary = "文档详情")
    public Result<KbDocumentVO> detail(@PathVariable Long documentId) {
        return Result.ok(knowledgeBaseService.getDocument(UserContext.userId(), documentId));
    }

    /**
     * 上传资料。
     *
     * <p>用 {@code @RequestPart} 而不是 {@code @RequestParam}：
     * 前端会以 {@code multipart/form-data} 提交，字段名固定为 {@code file}。</p>
     */
    @PostMapping("/upload")
    @Operation(summary = "上传资料",
            description = "支持 pdf/docx/txt/md，单文件上限 50MB。落盘后立即返回，后台异步解析入库")
    public Result<KbDocumentVO> upload(@RequestPart("file") MultipartFile file) {
        return Result.ok("上传成功，正在后台解析", knowledgeBaseService.upload(UserContext.userId(), file));
    }

    @PostMapping("/{documentId}/reingest")
    @Operation(summary = "重新解析", description = "解析失败后可重试；会覆盖原有切片与向量")
    public Result<Void> reingest(@PathVariable Long documentId) {
        knowledgeBaseService.reingest(UserContext.userId(), documentId);
        return Result.ok("已重新提交解析", null);
    }

    @DeleteMapping("/{documentId}")
    @Operation(summary = "删除资料", description = "同时清理向量库、切片表与原始文件")
    public Result<Void> delete(@PathVariable Long documentId) {
        knowledgeBaseService.deleteDocument(UserContext.userId(), documentId);
        return Result.ok("删除成功", null);
    }
}
