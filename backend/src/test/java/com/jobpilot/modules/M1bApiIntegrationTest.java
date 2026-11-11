package com.jobpilot.modules;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobpilot.AbstractIntegrationTest;
import com.jobpilot.modules.chat.dto.ChatStreamEvent;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * M1-b 端到端集成测试：真实 PostgreSQL + 本地假 OpenAI 上游。
 *
 * <p>覆盖 Provider/Model CRUD、连通测试、上游模型列举、全局默认、
 * 会话创建、SSE 流式对话、消息追溯、两级切换与 409 守卫。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class M1bApiIntegrationTest extends AbstractIntegrationTest {

    private static final String MODEL = "Qwen3.8-27B";

    private static HttpServer upstream;
    private static String upstreamBase;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private WebTestClient client;

    @BeforeAll
    static void startUpstream() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("\"stream\":true")) {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    write(out, "data: {\"model\":\"real-model\",\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n");
                    write(out, "data: {\"model\":\"real-model\",\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n");
                    write(out, "data: {\"model\":\"real-model\",\"choices\":[],\"usage\":{\"total_tokens\":7}}\n\n");
                    write(out, "data: [DONE]\n\n");
                }
            } else {
                respond(exchange, 200, """
                        {"model":"real-model","choices":[{"message":{"content":"ok"}}],
                         "usage":{"prompt_tokens":2,"completion_tokens":1,"total_tokens":3}}
                        """);
            }
        });
        upstream.createContext("/v1/models", exchange ->
                respond(exchange, 200, "{\"data\":[{\"id\":\"Qwen3.8-27B\"},{\"id\":\"stub-extra\"}]}"));
        upstream.createContext("/bad/chat/completions", exchange ->
                respond(exchange, 401, "{\"error\":\"invalid token\"}"));
        upstream.start();
        upstreamBase = "http://127.0.0.1:" + upstream.getAddress().getPort();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop(0);
    }

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
        jdbc.update("DELETE FROM chat_messages");
        jdbc.update("DELETE FROM chat_sessions");
        jdbc.update("DELETE FROM llm_call_logs");
        jdbc.update("DELETE FROM llm_models");
        jdbc.update("DELETE FROM llm_providers");
        jdbc.update("DELETE FROM llm_global_setting");
    }

    @Test
    @DisplayName("Provider/Model CRUD、连通测试、上游模型列举")
    void providerAndModelLifecycle() {
        createProvider("stub", upstreamBase + "/v1", "sk-12345678test")
                .jsonPath("$.data.providerKey").isEqualTo("stub")
                .jsonPath("$.data.apiKeyMasked").isEqualTo("sk-****test")
                .jsonPath("$.data.models").isEmpty();

        createModel("stub", MODEL).jsonPath("$.data.modelName").isEqualTo(MODEL);

        client.get().uri("/api/llm/providers").exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].providerKey").isEqualTo("stub")
                .jsonPath("$.data[0].models[0].modelName").isEqualTo(MODEL);

        // 连通测试回显上游真实 model，可识别网关别名
        client.post().uri("/api/llm/providers/stub/test").exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.success").isEqualTo(true)
                .jsonPath("$.data.upstreamModel").isEqualTo("real-model");

        client.get().uri("/api/llm/providers/stub/models").exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.source").isEqualTo("UPSTREAM")
                .jsonPath("$.data.models[1]").isEqualTo("stub-extra");

        // 省略 apiKey 时保持原密钥
        client.put().uri("/api/llm/providers/stub")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("label", "改个名字"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.apiKeyMasked").isEqualTo("sk-****test");

        client.post().uri("/api/llm/providers").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(providerBody("stub", upstreamBase + "/v1", "sk-1"))
                .exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody().jsonPath("$.code").isEqualTo(40903);

        client.post().uri("/api/llm/providers/stub/models").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("modelName", MODEL, "label", MODEL))
                .exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody().jsonPath("$.code").isEqualTo(40904);
    }

    @Test
    @DisplayName("全局默认、SSE 流式对话、消息追溯、两级切换与 409 守卫")
    void chatFlowAndSwitchGuards() {
        createProvider("stub", upstreamBase + "/v1", "sk-12345678test");
        createModel("stub", MODEL);
        client.put().uri("/api/llm/global").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("defaultProviderKey", "stub", "defaultModel", MODEL))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.defaultModel").isEqualTo(MODEL);

        long sessionId = createSession();

        List<ServerSentEvent<ChatStreamEvent>> events = client.post()
                .uri("/api/chat/sessions/{id}/messages", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(Map.of("content", "你好"))
                .exchange().expectStatus().isOk()
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<ChatStreamEvent>>() { })
                .getResponseBody()
                .collectList()
                .block(Duration.ofSeconds(30));

        assertThat(events).isNotNull();
        List<ChatStreamEvent> payloads = events.stream().map(ServerSentEvent::data).toList();
        assertThat(payloads.stream().filter(e -> "delta".equals(e.type())).map(ChatStreamEvent::content).toList())
                .containsExactly("你", "好");
        ChatStreamEvent done = payloads.stream().filter(e -> "done".equals(e.type())).findFirst().orElseThrow();
        assertThat(done.messageId()).isNotNull();
        assertThat(done.providerKey()).isEqualTo("stub");
        assertThat(done.model()).isEqualTo("real-model");
        assertThat(done.totalTokens()).isEqualTo(7);

        client.get().uri("/api/chat/sessions/{id}/messages", sessionId).exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(2)
                .jsonPath("$.data[0].role").isEqualTo("USER")
                .jsonPath("$.data[1].role").isEqualTo("ASSISTANT")
                .jsonPath("$.data[1].llmProvider").isEqualTo("stub")
                .jsonPath("$.data[1].llmModel").isEqualTo("real-model");

        switchModel(sessionId, HttpStatus.OK, null);

        jdbc.update("UPDATE chat_sessions SET status = 'PROCESSING' WHERE id = ?", sessionId);
        switchModel(sessionId, HttpStatus.CONFLICT, 40901);
        client.post().uri("/api/chat/sessions/{id}/messages", sessionId)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("content", "再问"))
                .exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody().jsonPath("$.code").isEqualTo(40901);

        jdbc.update("UPDATE chat_sessions SET status = 'LOCKED' WHERE id = ?", sessionId);
        switchModel(sessionId, HttpStatus.CONFLICT, 40902);

        client.delete().uri("/api/chat/sessions/{id}", sessionId).exchange().expectStatus().isOk();
        client.get().uri("/api/chat/sessions/{id}", sessionId).exchange()
                .expectStatus().isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("连通测试失败时返回 200 + success=false，且不回显密钥")
    void connectivityFailureIsReported() {
        createProvider("bad", upstreamBase + "/bad", "sk-secret-value");
        createModel("bad", MODEL);

        client.post().uri("/api/llm/providers/bad/test").exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.success").isEqualTo(false)
                .jsonPath("$.data.message").value(msg -> assertThat(String.valueOf(msg))
                        .contains("HTTP 401").doesNotContain("sk-secret-value"));
    }

    private WebTestClient.BodyContentSpec createProvider(String key, String baseUrl, String apiKey) {
        return client.post().uri("/api/llm/providers").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(providerBody(key, baseUrl, apiKey))
                .exchange().expectStatus().isOk().expectBody();
    }

    private WebTestClient.BodyContentSpec createModel(String providerKey, String model) {
        return client.post().uri("/api/llm/providers/{key}/models", providerKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("modelName", model, "label", model, "isDefault", true))
                .exchange().expectStatus().isOk().expectBody();
    }

    private long createSession() {
        JsonNode body = client.post().uri("/api/chat/sessions")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
                .exchange().expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
        assertThat(body).isNotNull();
        return body.path("data").path("id").asLong();
    }

    private void switchModel(long sessionId, HttpStatus status, Integer code) {
        WebTestClient.ResponseSpec spec = client.patch().uri("/api/chat/sessions/{id}/model", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("providerKey", "stub", "model", MODEL))
                .exchange().expectStatus().isEqualTo(status);
        if (code != null) {
            spec.expectBody().jsonPath("$.code").isEqualTo(code);
        }
    }

    private static Map<String, Object> providerBody(String key, String baseUrl, String apiKey) {
        return Map.of("providerKey", key, "label", key, "baseUrl", baseUrl, "apiKey", apiKey);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
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
