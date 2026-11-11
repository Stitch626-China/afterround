package com.jobpilot.common.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class StructuredOutputInvokerTest {

    /** 按队列依次返回预设回答，并记录每次收到的消息。 */
    private static final class ScriptedClient implements LlmClient {
        final Deque<String> answers = new ArrayDeque<>();
        final List<List<ChatMessage>> calls = new ArrayList<>();

        @Override
        public ChatResult chat(String model, List<ChatMessage> messages) {
            return chatJson(model, messages);
        }

        @Override
        public ChatResult chatJson(String model, List<ChatMessage> messages) {
            calls.add(List.copyOf(messages));
            return new ChatResult(answers.removeFirst(), model, null, null, null);
        }

        @Override
        public Flux<StreamChunk> stream(String model, List<ChatMessage> messages) {
            return Flux.empty();
        }

        @Override
        public List<String> listModels() {
            return List.of();
        }
    }

    private record Demo(String name) {
    }

    @Test
    void parsesFencedJsonOnFirstAttempt() {
        ScriptedClient client = new ScriptedClient();
        client.answers.add("```json\n{\"name\":\"ok\"}\n```");

        Demo result = new StructuredOutputInvoker(client)
                .invoke("m", List.of(ChatMessage.user("hi")), Demo.class);

        assertThat(result.name()).isEqualTo("ok");
        assertThat(client.calls).hasSize(1);
    }

    @Test
    void retriesWithErrorFeedback() {
        ScriptedClient client = new ScriptedClient();
        client.answers.add("不是 JSON");
        client.answers.add("{\"name\":\"fixed\"}");

        Demo result = new StructuredOutputInvoker(client)
                .invoke("m", List.of(ChatMessage.user("hi")), Demo.class);

        assertThat(result.name()).isEqualTo("fixed");
        assertThat(client.calls).hasSize(2);
        assertThat(client.calls.get(1)).last()
                .extracting(ChatMessage::content).asString().contains("无法解析为 JSON");
    }

    @Test
    void failsAfterThreeAttempts() {
        ScriptedClient client = new ScriptedClient();
        client.answers.add("x");
        client.answers.add("y");
        client.answers.add("z");

        assertThatThrownBy(() -> new StructuredOutputInvoker(client)
                .invoke("m", List.of(ChatMessage.user("hi")), Demo.class))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("连续 3 次失败");
        assertThat(client.calls).hasSize(3);
    }
}
