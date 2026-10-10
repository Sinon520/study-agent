package com.studyagent.common.exception;

import com.studyagent.common.Result;
import lombok.Getter;

import java.io.Serial;

/**
 * 业务异常：由业务代码主动抛出，由 {@link GlobalExceptionHandler} 统一转换为 {@link Result}。
 */
@Getter
public class BizException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业务状态码，默认 500 */
    private final Integer code;

    public BizException(String msg) {
        super(msg);
        this.code = Result.CODE_FAIL;
    }

    public BizException(Integer code, String msg) {
        super(msg);
        this.code = code;
    }

    public BizException(String msg, Throwable cause) {
        super(msg, cause);
        this.code = Result.CODE_FAIL;
    }

    public BizException(Integer code, String msg, Throwable cause) {
        super(msg, cause);
        this.code = code;
    }
}
