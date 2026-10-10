package com.studyagent.mq.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 错题异步归类消息（topic: {@code wrong-classify}）。
 *
 * <p>新增错题落库后发出；消费者调 Python {@code /agent/classify} 得到
 * 科目与知识点标签，再回写 {@code wrong_question_tag_rel}（唯一键幂等）。</p>
 *
 * <p>{@code traceId} 随消息体一起走：MQ 是跨线程、跨进程时间的异步边界，
 * MDC 到不了"将来某个消费者的线程"，只能把它序列化进消息，消费时重新入 MDC，
 * 日志才能和产生消息的那次 HTTP 请求对上（跨语言 traceId 链路的第三段）。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WrongClassifyMessage implements Serializable {

    private Long wrongQuestionId;

    /** 错题内容（题干文本），供 LLM 归类用 */
    private String content;

    /** 用户提交时选的科目（可为空，空则由 LLM 判断） */
    private String subject;

    /** 产生这条消息的请求链路 ID */
    private String traceId;
}
