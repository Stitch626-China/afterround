package com.jobpilot.infrastructure.asr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.jobpilot.common.llm.LlmException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** OpenAI 兼容 ASR：multipart 结构、JSON 解析、空文本与上游错误的处理。 */
class OpenAiCompatibleAsrClientTest {

    private static HttpServer server;
    private static String baseUrl;
    private static final AtomicReference<String> LAST_BODY = new AtomicReference<>();
    private static final AtomicReference<String> LAST_AUTH = new AtomicReference<>();

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/audio/transcriptions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            LAST_BODY.set(body);
            LAST_AUTH.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if (body.contains("EMPTY")) {
                respond(exchange, 200, "{\"text\":\"   \"}");
            } else if (body.contains("BOOM")) {
                respond(exchange, 500, "{\"error\":\"upstream boom\"}");
            } else {
                respond(exchange, 200, "{\"text\":\"你好，欢迎参加面试\"}");
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    private static OpenAiCompatibleAsrClient client(String baseUrl) {
        return new OpenAiCompatibleAsrClient(new AsrProperties(baseUrl, "test-key", "whisper-1", "zh", 30));
    }

    @Test
    void transcribesWithMultipartAndBearerAuth() {
        String text = client(baseUrl).transcribe("a.mp3", "audio/mpeg", new byte[]{1, 2, 3});

        assertThat(text).isEqualTo("你好，欢迎参加面试");
        assertThat(LAST_AUTH.get()).isEqualTo("Bearer test-key");
        assertThat(LAST_BODY.get()).contains("name=\"model\"").contains("whisper-1")
                .contains("name=\"language\"").contains("zh")
                .contains("filename=\"a.mp3\"");
    }

    @Test
    void rejectsBlankTextAndUpstreamError() {
        assertThatThrownBy(() -> client(baseUrl).transcribe("a.mp3", "audio/mpeg",
                "EMPTY".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("为空");
        assertThatThrownBy(() -> client(baseUrl).transcribe("a.mp3", "audio/mpeg",
                "BOOM".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("ASR 调用失败");
    }

    @Test
    void unconfiguredRejects() {
        AsrProperties properties = new AsrProperties("", "", "whisper-1", "zh", 30);
        OpenAiCompatibleAsrClient client = new OpenAiCompatibleAsrClient(properties);

        assertThat(client.configured()).isFalse();
        assertThatThrownBy(() -> client.transcribe("a.mp3", "audio/mpeg", new byte[]{1}))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("未配置");
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
}
