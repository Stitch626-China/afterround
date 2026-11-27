package com.jobpilot.common.llm;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 结构化输出收口（§6.5）：JSON 模式调用 → 本地修复 → 带错误反馈重试，最多 3 次。
 */
public class StructuredOutputInvoker {

    private static final int MAX_ATTEMPTS = 3;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final LlmClient client;

    public StructuredOutputInvoker(LlmClient client) {
        this.client = client;
    }

    /** 调用并解析为 {@code type}；失败抛 {@link LlmException}。 */
    public <T> T invoke(String model, List<ChatMessage> messages, Class<T> type) {
        return invokeWithUsage(model, messages, type).value();
    }

    /** 同 {@link #invoke}，额外返回最后一次上游响应用于 token 埋点。 */
    public <T> Parsed<T> invokeWithUsage(String model, List<ChatMessage> messages, Class<T> type) {
        List<ChatMessage> current = new ArrayList<>(messages);
        String lastError = "未知错误";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            ChatResult result = null;
            try {
                result = client.chatJson(model, current);
                return new Parsed<>(MAPPER.readValue(JsonOutputParser.repair(result.content()), type), result);
            } catch (Exception ex) {
                lastError = ex.getMessage();
                current = new ArrayList<>(messages);
                // 严格网关要求 system 只能在开头，错误反馈必须用 user 角色追加
                current.add(ChatMessage.user(
                        "上次输出无法解析为 JSON：" + lastError + "。请只输出一个 JSON 对象，不要代码围栏、不要解释。"));
                if (result != null && attempt == MAX_ATTEMPTS) {
                    throw new LlmException(502, "结构化输出连续 " + MAX_ATTEMPTS + " 次失败：" + lastError);
                }
            }
        }
        throw new LlmException(502, "结构化输出连续 " + MAX_ATTEMPTS + " 次失败：" + lastError);
    }

    /** 解析结果 + 最后一次上游响应。 */
    public record Parsed<T>(T value, ChatResult result) {
    }
}
