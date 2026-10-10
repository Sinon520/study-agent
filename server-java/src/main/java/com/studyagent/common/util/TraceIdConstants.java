package com.studyagent.common.util;

/**
 * traceId 相关的常量集中定义，避免各处硬编码。
 */
public final class TraceIdConstants {

    /** MDC 中的 key，logback pattern 使用 %X{traceId} 读取 */
    public static final String MDC_KEY = "traceId";

    /** 请求/响应头名称 */
    public static final String HEADER = "X-Trace-Id";

    private TraceIdConstants() {
    }
}
