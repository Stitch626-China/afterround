package com.jobpilot.common.llm;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 结构化输出收口（§6.5）：JSON 模式调用 → 本地修复 → 带错误反馈重试，最多 3 次。
 *
 * @param <T> 目标 DTO 类型
 */
public class StructuredOutputInvoker {

    private static final int MAX_ATTEMPTS = 3;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmClient client;

    public StructuredOutputInvoker(LlmClient client) {
        this.client = client;
    }

    /**
     * 调用模型并把回答解析为 {@code type}。
     *
     * @throws LlmException 连续 {@value #MAX_ATTEMPTS} 次失败
     */
    public <T> T invoke(String model, List<ChatMessage> messages, Class<T> type) {
        List<ChatMessage> current = new ArrayList<>(messages);
        String lastError = "未知错误";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                ChatResult result = client.chatJson(model, current);
                return MAPPER.readValue(JsonOutputParser.repair(result.content()), type);
            } catch (Exception ex) {
                lastError = ex.getMessage();
                current = new ArrayList<>(messages);
                current.add(ChatMessage.system(
                        "上次输出无法解析为 JSON：" + lastError + "。请只输出一个 JSON 对象，不要代码围栏、不要解释。"));
            }
        }
        throw new LlmException(502, "结构化输出连续 " + MAX_ATTEMPTS + " 次失败：" + lastError);
    }
}
