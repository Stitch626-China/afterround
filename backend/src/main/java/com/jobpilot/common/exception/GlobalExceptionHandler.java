package com.jobpilot.common.exception;

import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.api.Result;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * 全局异常处理：把各类异常统一转换为 {@link Result}，并映射到正确的 HTTP 状态。
 *
 * <p>原则：可预期错误（参数、业务规则、上传限制）返回明确业务码；
 * 未预期异常一律 50000，日志保留堆栈，但**响应体不暴露内部细节**（NFR3）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：业务码与 HTTP 状态均由 ErrorCode 决定。 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException ex) {
        ErrorCode code = ex.errorCode();
        log.warn("业务异常 code={} message={}", code.code(), ex.getMessage());
        return ResponseEntity.status(code.httpStatus()).body(Result.fail(code, ex.getMessage()));
    }

    /** 请求体校验失败（@Valid）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleInvalidBody(MethodArgumentNotValidException ex) {
        FieldError first = ex.getBindingResult().getFieldError();
        String detail = first == null ? ErrorCode.BAD_REQUEST.message()
                : first.getField() + " " + first.getDefaultMessage();
        return badRequest(detail);
    }

    /** 方法参数/路径变量校验失败。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraint(ConstraintViolationException ex) {
        return badRequest(ex.getMessage());
    }

    /** 缺少必要参数或表单字段。 */
    @ExceptionHandler({MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    public ResponseEntity<Result<Void>> handleMissing(Exception ex) {
        return badRequest(ex.getMessage());
    }

    /** 上传文件超过 spring.servlet.multipart 限制（FR1.1：单文件 ≤ 5MB）。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleTooLarge(MaxUploadSizeExceededException ex) {
        ErrorCode code = ErrorCode.PAYLOAD_TOO_LARGE;
        log.warn("上传文件超过大小限制: {}", ex.getMessage());
        return ResponseEntity.status(code.httpStatus()).body(Result.fail(code));
    }

    /** 兜底：未预期异常，记录堆栈但不外泄细节。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpected(Exception ex) {
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        log.error("未预期异常", ex);
        return ResponseEntity.status(code.httpStatus()).body(Result.fail(code));
    }

    private ResponseEntity<Result<Void>> badRequest(String detail) {
        ErrorCode code = ErrorCode.BAD_REQUEST;
        log.warn("参数校验失败: {}", detail);
        return ResponseEntity.status(code.httpStatus()).body(Result.fail(code, detail));
    }
}
