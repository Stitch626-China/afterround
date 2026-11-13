package com.jobpilot.common.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleClientTest {

    private static HttpServer server;
    private static String baseUrl;
    private static final AtomicReference<String> LAST_BODY = new AtomicReference<>();
    private static final AtomicReference<String> LAST_AUTH = new AtomicReference<>();

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            LAST_BODY.set(body);
            LAST_AUTH.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if (body.contains("\"stream\":true")) {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    write(out, "data: {\"model\":\"real-model\",\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n");
                    write(out, "data: {\"model\":\"real-model\",\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n");
                    write(out, "data: {\"model\":\"real-model\",\"choices\":[],\"usage\":{\"total_tokens\":7}}\n\n");
                    write(out, "data: [DONE]\n\n");
                }
                return;
            }
            respond(exchange, 200, """
                    {"model":"real-model","choices":[{"message":{"content":"你好"}}],
                     "usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}
                    """);
        });
        server.createContext("/v1/models", exchange ->
                respond(exchange, 200, "{\"data\":[{\"id\":\"Qwen3.8-27B\"},{\"id\":\"other\"}]}"));
        server.createContext("/v1/nodone/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                write(out, "data: {\"choices\":[{\"delta\":{\"content\":\"残\"}}]}\n\n");
            }
        });
        server.createContext("/v1/errorframe/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                write(out, "data: {\"error\":{\"message\":\"secret-internal\"}}\n\n");
            }
        });
        server.createContext("/bad/chat/completions", exchange ->
                respond(exchange, 401, "{\"error\":\"invalid token\"}"));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @Test
    void chatParsesContentModelAndUsage() {
        ChatResult result = new OpenAiCompatibleClient(baseUrl + "/v1", "sk-test")
                .chat("requested-model", List.of(ChatMessage.user("hi")));

        assertThat(result.content()).isEqualTo("你好");
        assertThat(result.model()).isEqualTo("real-model");
        assertThat(result.totalTokens()).isEqualTo(5);
        assertThat(LAST_AUTH.get()).isEqualTo("Bearer sk-test");
    }

    @Test
    void chatJsonRequestsJsonObjectFormat() {
        new OpenAiCompatibleClient(baseUrl + "/v1", "sk-test")
                .chatJson("m", List.of(ChatMessage.user("hi")));

        assertThat(LAST_BODY.get()).contains("\"response_format\":{\"type\":\"json_object\"}");
        assertThat(LAST_BODY.get()).contains("\"temperature\":0.2");
        assertThat(LAST_BODY.get()).contains("\"max_tokens\":4096");
    }

    @Test
    void streamEmitsDeltasAndUsage() {
        List<StreamChunk> chunks = new OpenAiCompatibleClient(baseUrl + "/v1", "sk-test")
                .stream("m", List.of(ChatMessage.user("hi")))
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(chunks).extracting(StreamChunk::content).containsExactly("你", "好", null);
        assertThat(chunks.get(2).totalTokens()).isEqualTo(7);
        assertThat(chunks.get(0).model()).isEqualTo("real-model");
        assertThat(LAST_BODY.get()).contains("\"max_tokens\":4096");
    }

    @Test
    void streamWithoutDoneMarkerFails() {
        assertThatThrownBy(() -> new OpenAiCompatibleClient(baseUrl + "/v1/nodone", "sk-test")
                .stream("m", List.of(ChatMessage.user("hi")))
                .collectList()
                .block(Duration.ofSeconds(10)))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("未正常结束");
    }

    @Test
    void streamErrorFrameIsSanitized() {
        assertThatThrownBy(() -> new OpenAiCompatibleClient(baseUrl + "/v1/errorframe", "sk-test")
                .stream("m", List.of(ChatMessage.user("hi")))
                .collectList()
                .block(Duration.ofSeconds(10)))
                .isInstanceOf(LlmException.class)
                .hasMessage("上游流式响应返回错误")
                .hasMessageNotContaining("secret-internal");
    }

    @Test
    void listModelsReturnsIds() {
        assertThat(new OpenAiCompatibleClient(baseUrl + "/v1", "sk-test").listModels())
                .containsExactly("Qwen3.8-27B", "other");
    }

    @Test
    void httpErrorIsSanitized() {
        LlmClient client = new OpenAiCompatibleClient(baseUrl + "/bad", "sk-secret-value");

        assertThatThrownBy(() -> client.chat("m", List.of(ChatMessage.user("hi"))))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("HTTP 401")
                .hasMessageNotContaining("sk-secret-value");
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
