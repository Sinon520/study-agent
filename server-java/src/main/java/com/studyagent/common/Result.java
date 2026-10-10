package com.studyagent.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.slf4j.MDC;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一返回体。
 * <p>约定：HTTP 状态码恒为 200，业务状态通过 body 中的 {@code code} 表达，前端统一拦截处理。</p>
 *
 * @param <T> 业务数据类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "统一返回体")
public class Result<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业务状态码：200 成功，其余为失败 */
    public static final int CODE_SUCCESS = 200;
    /** 通用失败码 */
    public static final int CODE_FAIL = 500;
    /** 参数校验失败码 */
    public static final int CODE_BAD_REQUEST = 400;
    /** 未认证 */
    public static final int CODE_UNAUTHORIZED = 401;
    /** 无权限 */
    public static final int CODE_FORBIDDEN = 403;

    @Schema(description = "业务状态码，200 表示成功", example = "200")
    private Integer code;

    @Schema(description = "提示信息", example = "success")
    private String msg;

    @Schema(description = "业务数据")
    private T data;

    @Schema(description = "链路追踪 ID，与响应头 X-Trace-Id 一致")
    private String traceId;

    /** 从 MDC 中取当前请求的 traceId */
    private static String currentTraceId() {
        try {
            return MDC.get("traceId");
        } catch (Exception e) {
            // MDC 不可用时不影响主流程
            return null;
        }
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(CODE_SUCCESS, "success", data, currentTraceId());
    }

    public static <T> Result<T> ok(String msg, T data) {
        return new Result<>(CODE_SUCCESS, msg, data, currentTraceId());
    }

    public static <T> Result<T> fail(String msg) {
        return new Result<>(CODE_FAIL, msg, null, currentTraceId());
    }

    public static <T> Result<T> fail(Integer code, String msg) {
        return new Result<>(code, msg, null, currentTraceId());
    }
}
