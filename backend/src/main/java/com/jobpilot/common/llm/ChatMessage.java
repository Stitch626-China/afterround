package com.jobpilot.common.llm;

/**
 * OpenAI 兼容协议中的一条对话消息。
 *
 * @param role    system / user / assistant
 * @param content 消息内容
 */
public record ChatMessage(String role, String content) {

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }
}
