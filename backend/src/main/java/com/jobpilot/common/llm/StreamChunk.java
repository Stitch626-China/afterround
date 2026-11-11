package com.jobpilot.common.llm;

/**
 * 流式响应中的一个分块。
 *
 * @param content     增量文本，可能为 null（仅携带 model / usage 的分块）
 * @param model       上游回显的模型名，可能为 null
 * @param totalTokens 总 token，仅最后一个分块可能携带，可能为 null
 */
public record StreamChunk(String content, String model, Integer totalTokens) {
}
