package com.studyagent.common.exception;

import com.studyagent.common.Result;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/**
 * 全局异常处理：统一返回 HTTP 200 + 业务 code，前端只需处理 body。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常 */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBizException(BizException e) {
        log.warn("[业务异常] code={}, msg={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /** @Valid @RequestBody 校验失败：取第一个字段错误信息 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("参数校验失败");
        log.warn("[参数校验失败] {}", msg);
        return Result.fail(Result.CODE_BAD_REQUEST, msg);
    }

    /** 表单/查询参数绑定校验失败 */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("参数绑定失败");
        log.warn("[参数绑定失败] {}", msg);
        return Result.fail(Result.CODE_BAD_REQUEST, msg);
    }

    /** @Validated 在方法参数上的约束校验失败 */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolation(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        if (msg.isBlank()) {
            msg = "参数校验失败";
        }
        log.warn("[约束校验失败] {}", msg);
        return Result.fail(Result.CODE_BAD_REQUEST, msg);
    }

    /** 请求体不是合法 JSON / 类型不匹配 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("[请求体解析失败] {}", e.getMessage());
        return Result.fail(Result.CODE_BAD_REQUEST, "请求体格式错误，请检查 JSON 是否合法");
    }

    /** 必填请求参数缺失 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        String msg = "缺少必填参数：" + e.getParameterName();
        log.warn("[缺少参数] {}", msg);
        return Result.fail(Result.CODE_BAD_REQUEST, msg);
    }

    /** 参数类型不匹配 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String msg = "参数类型错误：" + e.getName();
        log.warn("[参数类型错误] {}", msg);
        return Result.fail(Result.CODE_BAD_REQUEST, msg);
    }

    /** 兜底：打印完整堆栈，方便排查 */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("[系统异常] 未捕获异常", e);
        return Result.fail(Result.CODE_FAIL, "服务器内部错误，请联系管理员");
    }
}
