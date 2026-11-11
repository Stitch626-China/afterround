package com.jobpilot.common.llm;

/**
 * 上游模型调用失败。
 *
 * <p>message 只保留状态码与响应摘要，**绝不包含 API Key**（FRX.10）。
 */
public class LlmException extends RuntimeException {

    private final int status;

    public LlmException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
