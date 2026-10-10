package com.studyagent.mq.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 自检环回消息（topic: {@code mq-selfcheck}）：生产者→消费者收发验证用，不承载业务。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SelfCheckMessage implements Serializable {

    /** 本次自检的唯一 ID，生产端用它等待消费者回执 */
    private String checkId;

    /** 探测载荷（原样带回，验证序列化/反序列化没有损坏） */
    private String payload;

    /** 发送时刻（毫秒时间戳），消费端可算出"消息投递耗时" */
    private long sentAt;

    /** 产生这条消息的请求链路 ID */
    private String traceId;
}
