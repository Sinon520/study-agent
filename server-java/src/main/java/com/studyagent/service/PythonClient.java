package com.studyagent.service;

import com.studyagent.model.vo.ClassifyResultVO;
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

    /**
     * 调用规划 Agent 生成逐日计划（**只生成不落库**）。
     *
     * <p>落库由 Java 自己做（{@code /plan/agent-generate}），因为：
     * 用户侧要的是"校验登录用户 → 生成 → 落库"的完整事务语义，
     * 如果让 Python 拿着 userId 直接调内部落库接口，就绕过了用户上下文，
     * 也不便于在同一处做配额与审计。</p>
     *
     * @return Python 原始响应（含 tasks / warnings / retries 等字段）
     */
    com.fasterxml.jackson.databind.JsonNode generatePlan(java.util.Map<String, Object> payload);

    /**
     * 错题 AI 归类：科目建议 + 知识点标签 + 难度估计。
     *
     * <p>由 RocketMQ 消费者（{@code WrongClassifyConsumer}）在收到
     * {@code wrong-classify} 消息后调用，结果回写标签关系表（Day 23）。</p>
     *
     * @param wrongQuestionId 错题 ID（透传给 Python 只用于日志串联）
     * @param content         错题题干文本
     * @param subject         学生填写的科目（可为空）
     */
    ClassifyResultVO classify(Long wrongQuestionId, String content, String subject);
}
