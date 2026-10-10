package com.studyagent.service;

import com.studyagent.entity.KbDocument;
import com.studyagent.model.dto.PageQuery;
import com.studyagent.model.vo.KbDocumentVO;
import com.studyagent.model.vo.PageResult;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 知识库文档服务。
 */
public interface KnowledgeBaseService {

    /** 分页查询我的文档 */
    PageResult<KbDocumentVO> pageDocuments(Long userId, PageQuery query, String parseStatus);

    /** 文档详情 */
    KbDocumentVO getDocument(Long userId, Long documentId);

    /**
     * 上传文件并落库（状态=待解析），随后**异步**触发 Python 解析入库。
     *
     * @return 文档视图（此时 parseStatus 可能是「待解析」或「解析中」）
     */
    KbDocumentVO upload(Long userId, MultipartFile file);

    /** 重新解析（解析失败后重试） */
    void reingest(Long userId, Long documentId);

    /** 删除文档：先删向量，再删切片，最后删文档记录 */
    void deleteDocument(Long userId, Long documentId);

    /** 取当前用户拥有的文档，不存在或不属于该用户时抛异常 */
    KbDocument requireOwnedDocument(Long userId, Long documentId);

    /** 按 ID 批量取文档名（内部接口，供 Python 生成引用溯源） */
    Map<Long, String> getDocumentNames(java.util.List<Long> documentIds);
}
