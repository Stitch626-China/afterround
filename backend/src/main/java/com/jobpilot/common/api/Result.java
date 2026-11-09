package com.jobpilot.common.api;

import com.jobpilot.common.web.TraceIdFilter;
import org.slf4j.MDC;

/**
 * 统一响应包装，对应契约中的 {@code Result<T>}。
 *
 * <p>{@code traceId} 自动取自 MDC，贯穿本请求全链路日志（NFR2）。
 *
 * @param <T> 业务数据类型
 */
public record Result<T>(int code, String message, T data, String traceId) {

    public static <T> Result<T> ok(T data) {
        return new Result<>(ErrorCode.OK.code(), ErrorCode.OK.message(), data, currentTraceId());
    }

    public static Result<Void> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return fail(errorCode, errorCode.message());
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.code(), message, null, currentTraceId());
    }

    public boolean isSuccess() {
        return code == ErrorCode.OK.code();
    }

    private static String currentTraceId() {
        return MDC.get(TraceIdFilter.MDC_KEY);
    }
}
