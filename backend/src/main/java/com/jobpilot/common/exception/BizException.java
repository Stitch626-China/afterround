package com.jobpilot.common.exception;

import com.jobpilot.common.api.ErrorCode;

/**
 * 业务异常：携带 {@link ErrorCode}，由全局异常处理器转换为统一响应。
 *
 * <p>只用于可预期的业务规则违反，不用于编程错误。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public static BizException of(ErrorCode errorCode) {
        return new BizException(errorCode);
    }

    public static BizException of(ErrorCode errorCode, String message) {
        return new BizException(errorCode, message);
    }
}
