package com.studyagent.service;

import com.studyagent.model.vo.KbIngestResultVO;

/**
 * Python AI 服务的客户端（Java → Python 主动调用）。
 *
 * <p>与 {@code PythonCallback} 方向相反：这里是 Java 发请求给 Python。
 * 保持接口最小化，只暴露"需要 Python 能力"的动作。</p>
 */
public interface PythonClient {

    /**
     * 触发文档解析入库（同步调用，可能耗时较长）。
     *
     * @param documentId   文档 ID
     * @param userId       所属用户
     * @param filePath     服务器上的绝对路径
     * @param chunkSize    分块大小，null 用 Python 默认
     * @param chunkOverlap 分块重叠，null 用 Python 默认
     */
    KbIngestResultVO ingest(Long documentId, Long userId, String filePath,
                            Integer chunkSize, Integer chunkOverlap);

    /** 删除某文档在向量库里的全部切片 */
    void deleteVectors(Long documentId);
}
