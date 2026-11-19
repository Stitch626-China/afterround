package com.jobpilot.common.llm;

/**
 * OpenAI 兼容协议中的一条对话消息。
 *
 * @param role     system / user / assistant
 * @param content  消息内容
 * @param imageUrl 图片输入（data URL 或公网地址）；文本消息为 null
 */
public record ChatMessage(String role, String content, String imageUrl) {

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, null);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content, null);
    }

    /** 视觉消息（FR6.1 图片提取）：content 为提示文本，imageUrl 为 data URL。 */
    public static ChatMessage userWithImage(String content, String imageUrl) {
        return new ChatMessage("user", content, imageUrl);
    }
}
