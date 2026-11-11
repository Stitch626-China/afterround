package com.jobpilot.modules.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** SSE 事件载荷（契约 ChatStreamEvent）：delta 增量 / done 结束 / error 失败。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatStreamEvent(
        String type,
        String content,
        Long messageId,
        String providerKey,
        String model,
        Integer totalTokens,
        Integer code,
        String message) {

    public static ChatStreamEvent delta(String content) {
        return new ChatStreamEvent("delta", content, null, null, null, null, null, null);
    }

    public static ChatStreamEvent done(Long messageId, String providerKey, String model, Integer totalTokens) {
        return new ChatStreamEvent("done", null, messageId, providerKey, model, totalTokens, null, null);
    }

    public static ChatStreamEvent error(int code, String message) {
        return new ChatStreamEvent("error", null, null, null, null, null, code, message);
    }
}
