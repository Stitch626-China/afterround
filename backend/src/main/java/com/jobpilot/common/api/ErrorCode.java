package com.jobpilot.common.api;

import org.springframework.http.HttpStatus;

/**
 * 业务错误码。
 *
 * <p>码段约定：前三位对应 HTTP 状态，后两位为该状态下的具体业务原因。
 * 所有码值必须与 {@code contracts/openapi.yaml} 中注明的业务码保持一致。
 */
public enum ErrorCode {

    OK(0, "ok", HttpStatus.OK),

    BAD_REQUEST(40001, "参数校验失败", HttpStatus.BAD_REQUEST),

    NOT_FOUND(40401, "资源不存在", HttpStatus.NOT_FOUND),

    /** 会话正在执行异步任务，禁止切换模型（FRX.6）。 */
    SESSION_SWITCH_FORBIDDEN(40901, "任务执行中，请等待完成后再切换", HttpStatus.CONFLICT),

    /** 会话已进入终态，禁止切换与继续发送。 */
    SESSION_LOCKED(40902, "会话已锁定，不可操作", HttpStatus.CONFLICT),

    PROVIDER_EXISTS(40903, "Provider 标识已存在", HttpStatus.CONFLICT),

    MODEL_EXISTS(40904, "该 Provider 下已存在同名模型", HttpStatus.CONFLICT),

    /** 被进行中的会话或全局默认引用，不可删除。 */
    RESOURCE_REFERENCED(40905, "资源正被引用，不可删除", HttpStatus.CONFLICT),

    /** 公司名称全等查重冲突（M2，companies.name UNIQUE）。 */
    COMPANY_EXISTS(40906, "公司名称已存在", HttpStatus.CONFLICT),

    /** 简历对比要求两份分析都已完成（M2 F7）。 */
    ANALYSIS_NOT_COMPLETED(40907, "简历分析未完成，无法对比", HttpStatus.CONFLICT),

    /** JD 解析 / 匹配等异步任务已在执行中（M2 F2）。 */
    TASK_IN_PROGRESS(40908, "任务执行中，请等待完成", HttpStatus.CONFLICT),

    /** 简历分析任务已在执行中（FR1.5）。 */
    ANALYSIS_IN_PROGRESS(40910, "分析任务进行中", HttpStatus.CONFLICT),

    /** 模拟面试出题 / 追问生成中，或已交卷评估中（F3）。 */
    INTERVIEW_TASK_IN_PROGRESS(40911, "面试任务执行中，请等待完成", HttpStatus.CONFLICT),

    /** 模拟面试已评估完成，答案与模型均锁定（F3）。 */
    INTERVIEW_FINISHED(40912, "面试已结束，不可再操作", HttpStatus.CONFLICT),

    /** 模拟面试当前状态不允许该操作（F3）。 */
    INTERVIEW_STATE_INVALID(40913, "当前面试状态不允许该操作", HttpStatus.CONFLICT),

    /** Offer 保留组最多 4 个（F6）。 */
    OFFER_KEEP_LIMIT(40914, "保留组最多 4 个 Offer", HttpStatus.CONFLICT),

    PAYLOAD_TOO_LARGE(41301, "文件超过大小限制", HttpStatus.PAYLOAD_TOO_LARGE),

    UNSUPPORTED_MEDIA_TYPE(41501, "不支持的文件类型", HttpStatus.UNSUPPORTED_MEDIA_TYPE),

    INTERNAL_ERROR(50000, "服务内部错误", HttpStatus.INTERNAL_SERVER_ERROR);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(int code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
