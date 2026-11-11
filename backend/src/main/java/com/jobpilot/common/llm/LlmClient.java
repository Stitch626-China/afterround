package com.jobpilot.common.llm;

import java.util.List;

import reactor.core.publisher.Flux;

/**
 * 模型客户端抽象：隔离 OpenAI 兼容协议细节，便于替换实现或测试打桩。
 */
public interface LlmClient {

    /** 非流式对话。 */
    ChatResult chat(String model, List<ChatMessage> messages);

    /** 非流式对话，要求上游返回 JSON 对象（{@code response_format=json_object}）。 */
    ChatResult chatJson(String model, List<ChatMessage> messages);

    /** 流式对话，分块完成时 Flux 结束。 */
    Flux<StreamChunk> stream(String model, List<ChatMessage> messages);

    /** 拉取上游可用模型名列表。 */
    List<String> listModels();
}
