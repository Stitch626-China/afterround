package com.jobpilot.common.llm;

/**
 * 一次非流式对话的结果。
 *
 * @param content          模型回答文本
 * @param model            上游响应中回显的真实模型名（可用于识别网关别名）
 * @param promptTokens     输入 token，上游未返回时为 null
 * @param completionTokens 输出 token，上游未返回时为 null
 * @param totalTokens      总 token，上游未返回时为 null
 */
public record ChatResult(
        String content,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens) {
}
