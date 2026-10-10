package com.studyagent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.common.Result;
import com.studyagent.common.exception.BizException;
import com.studyagent.config.AppProperties;
import com.studyagent.entity.KbChunk;
import com.studyagent.entity.KbDocument;
import com.studyagent.mapper.KbDocumentMapper;
import com.studyagent.model.dto.PageQuery;
import com.studyagent.model.vo.KbDocumentVO;
import com.studyagent.model.vo.KbIngestResultVO;
import com.studyagent.model.vo.PageResult;
import com.studyagent.service.KbChunkService;
import com.studyagent.service.KbDocumentService;
import com.studyagent.service.KnowledgeBaseService;
import com.studyagent.service.PythonClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 知识库文档服务实现。
 *
 * <h3>上传 → 解析 的两段式设计</h3>
 * <ol>
 *   <li><b>同步段</b>：校验文件、落盘、写 {@code kb_document}（状态=待解析），
 *       立刻把响应返回给前端。用户不用等解析完成。</li>
 *   <li><b>异步段</b>：在线程池里调 Python {@code /rag/ingest}，成功后状态变「完成」。</li>
 * </ol>
 *
 * <p>为什么本周用线程池而不是 RocketMQ：RocketMQ 的接入排在第 4 周，
 * 而"上传后异步解析"这个交互本周就要能用。用线程池先跑通，
 * 第 4 周把 {@link #triggerIngestAsync} 换成发 {@code kb-ingest} 消息即可，
 * 其余代码不动（这也是刻意把它抽成独立私有方法的理由）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl extends ServiceImpl<KbDocumentMapper, KbDocument>
        implements KnowledgeBaseService {

    /** 允许上传的扩展名（与 Python 侧 document.py 的支持列表保持一致） */
    private static final List<String> ALLOWED_EXTENSIONS = List.of("pdf", "docx", "doc", "txt", "md");

    /** 单文件大小上限：50MB（application.yml 的 multipart 也配了同样的值） */
    private static final long MAX_FILE_SIZE = 50L * 1024 * 1024;

    /**
     * 解析任务线程池。
     * <p>用小池子而不是 {@code new Thread}：解析是 CPU 密集（本地 BGE-M3 跑 CPU），
     * 并发太高只会互相抢 CPU，串行度控制在 2 更稳。队列用
     * {@code CallerRunsPolicy} 语义（这里用固定池 + 有界队列会丢任务，故直接固定池）。</p>
     */
    private final ExecutorService ingestExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "kb-ingest-" + UUID.randomUUID().toString().substring(0, 6));
        thread.setDaemon(true);
        return thread;
    });

    private final KbDocumentService kbDocumentService;
    private final KbChunkService kbChunkService;
    private final PythonClient pythonClient;
    private final AppProperties appProperties;

    // ------------------------------------------------------------------ 查询

    @Override
    public PageResult<KbDocumentVO> pageDocuments(Long userId, PageQuery query, String parseStatus) {
        Page<KbDocument> page = query.toPage();
        Page<KbDocument> result = page(page, Wrappers.<KbDocument>lambdaQuery()
                .eq(KbDocument::getUserId, userId)
                .eq(StringUtils.hasText(parseStatus), KbDocument::getParseStatus, parseStatus)
                .orderByDesc(KbDocument::getId));
        return PageResult.of(result, KbDocumentVO::from);
    }

    @Override
    public KbDocumentVO getDocument(Long userId, Long documentId) {
        return KbDocumentVO.from(requireOwnedDocument(userId, documentId));
    }

    @Override
    public Map<Long, String> getDocumentNames(List<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return Map.of();
        }
        List<KbDocument> documents = list(Wrappers.<KbDocument>lambdaQuery()
                .in(KbDocument::getId, documentIds)
                .select(KbDocument::getId, KbDocument::getFileName));
        Map<Long, String> names = new HashMap<>(documents.size());
        for (KbDocument document : documents) {
            names.put(document.getId(), document.getFileName());
        }
        return names;
    }

    // ------------------------------------------------------------------ 上传

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KbDocumentVO upload(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(Result.CODE_BAD_REQUEST, "上传文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BizException(Result.CODE_BAD_REQUEST,
                    "文件大小 " + (file.getSize() / 1024 / 1024) + "MB 超过上限 50MB");
        }

        String originalName = StringUtils.hasText(file.getOriginalFilename())
                ? Paths.get(file.getOriginalFilename()).getFileName().toString()
                : "unnamed";
        String extension = extensionOf(originalName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BizException(Result.CODE_BAD_REQUEST,
                    "不支持的文件类型 ." + extension + "，仅支持 " + String.join("/", ALLOWED_EXTENSIONS));
        }

        // 落盘：按用户分目录，文件名加 UUID 前缀避免同名覆盖（也避免中文名在磁盘上的编码问题）
        Path targetDir = Paths.get(appProperties.getUploadDir(), "kb", String.valueOf(userId)).toAbsolutePath();
        Path targetFile;
        try {
            Files.createDirectories(targetDir);
            targetFile = targetDir.resolve(UUID.randomUUID().toString().replace("-", "") + "." + extension);
            file.transferTo(targetFile.toFile());
        } catch (IOException e) {
            log.error("上传文件落盘失败 userId={} name={}", userId, originalName, e);
            throw new BizException(Result.CODE_FAIL, "文件保存失败：" + e.getMessage());
        }

        KbDocument document = new KbDocument();
        document.setUserId(userId);
        document.setFileName(originalName);
        document.setFilePath(targetFile.toString());
        document.setFileType(extension);
        document.setFileSize(file.getSize());
        document.setParseStatus("待解析");
        document.setChunkCount(0);
        document.setUploadTime(LocalDateTime.now());
        save(document);

        log.info("文档上传成功 userId={} documentId={} name={} size={}B path={}",
                userId, document.getId(), originalName, file.getSize(), targetFile);

        // 先标记「解析中」再异步解析：让前端立刻看到进度在动
        markStatus(document, "解析中", null, null);
        triggerIngestAsync(document.getId(), userId, document.getFilePath());

        return KbDocumentVO.from(document);
    }

    @Override
    public void reingest(Long userId, Long documentId) {
        KbDocument document = requireOwnedDocument(userId, documentId);
        if (!Files.exists(Paths.get(document.getFilePath()))) {
            throw new BizException(Result.CODE_BAD_REQUEST, "原始文件已不存在，请重新上传：" + document.getFilePath());
        }
        markStatus(document, "解析中", null, null);
        triggerIngestAsync(documentId, userId, document.getFilePath());
        log.info("手动触发重新解析 documentId={} userId={}", documentId, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDocument(Long userId, Long documentId) {
        KbDocument document = requireOwnedDocument(userId, documentId);

        // 删除顺序：向量 → 切片 → 文档记录。
        // 向量先删：即使后面两步失败，也不会出现"检索命中但原文已删"的空引用。
        pythonClient.deleteVectors(documentId);
        kbChunkService.remove(Wrappers.<KbChunk>lambdaQuery().eq(KbChunk::getDocumentId, documentId));
        removeById(documentId);

        // 原始文件也删掉，避免磁盘上堆积没人引用的资料
        try {
            Files.deleteIfExists(Paths.get(document.getFilePath()));
        } catch (IOException e) {
            log.warn("删除原始文件失败（不影响记录删除）path={}: {}", document.getFilePath(), e.getMessage());
        }

        log.info("删除文档完成 documentId={} userId={}", documentId, userId);
    }

    @Override
    public KbDocument requireOwnedDocument(Long userId, Long documentId) {
        KbDocument document = getById(documentId);
        if (document == null) {
            throw new BizException(Result.CODE_BAD_REQUEST, "文档不存在或已被删除：" + documentId);
        }
        if (!document.getUserId().equals(userId)) {
            log.warn("水平越权拦截：userId={} 试图访问 documentId={}（属于 userId={}）",
                    userId, documentId, document.getUserId());
            throw new BizException(Result.CODE_FORBIDDEN, "无权访问该文档");
        }
        return document;
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 异步触发 Python 解析入库。
     *
     * <p>第 4 周把这里换成发 RocketMQ {@code kb-ingest} 消息即可，
     * 调用方（{@link #upload}）完全不用改。</p>
     */
    private void triggerIngestAsync(Long documentId, Long userId, String filePath) {
        ingestExecutor.submit(() -> {
            long started = System.currentTimeMillis();
            try {
                KbIngestResultVO result = pythonClient.ingest(documentId, userId, filePath, null, null);
                log.info("异步解析完成 documentId={} 分块={} 向量={} 耗时={}ms",
                        documentId, result.getChunkCount(), result.getVectorCount(),
                        System.currentTimeMillis() - started);
            } catch (Exception e) {
                // Python 侧失败时也会回调把状态置为失败；这里再兜一层，
                // 防止"连 Python 都连不上"导致状态永远停在「解析中」
                log.error("异步解析失败 documentId={} 耗时={}ms", documentId,
                        System.currentTimeMillis() - started, e);
                String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                markStatusById(documentId, "失败", null, reason);
            }
        });
    }

    /** 更新状态并把变更同步到内存对象（避免调用方拿到的 VO 还是旧状态） */
    private void markStatus(KbDocument document, String status, Integer chunkCount, String errorMsg) {
        document.setParseStatus(status);
        if (chunkCount != null) {
            document.setChunkCount(chunkCount);
        }
        document.setErrorMsg(errorMsg);
        kbDocumentService.updateById(document);
    }

    private void markStatusById(Long documentId, String status, Integer chunkCount, String errorMsg) {
        KbDocument patch = new KbDocument();
        patch.setId(documentId);
        patch.setParseStatus(status);
        if (chunkCount != null) {
            patch.setChunkCount(chunkCount);
        }
        patch.setErrorMsg(errorMsg == null ? null : errorMsg.substring(0, Math.min(500, errorMsg.length())));
        kbDocumentService.updateById(patch);
    }

    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
