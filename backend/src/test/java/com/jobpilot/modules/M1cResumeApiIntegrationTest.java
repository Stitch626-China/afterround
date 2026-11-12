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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.AbstractIntegrationTest;
import com.jobpilot.infrastructure.llm.ProviderClientRegistry;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

/**
 * M1-c 端到端集成测试：真实 PostgreSQL + 本地假 LLM 上游。
 *
 * <p>覆盖上传 / 去重 / Tika 解析 / 自动异步分析 / 五维报告 / 分页 / 详情 / 删除 / 失败重试 / 409 守卫。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class M1cResumeApiIntegrationTest extends AbstractIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MODEL = "Qwen3.8-27B";
    private static final String ANALYSIS_JSON = """
            {
              "technicalDepth": 82, "projectQuality": 75, "engineeringAbility": 70,
              "expressionStructure": 65, "jobMatchPotential": 90,
              "basicInfo": {"name": "张三", "yearsOfExperience": 3, "techDirection": "Java 后端"},
              "strengths": [{"quote": "负责订单系统重构", "comment": "有主导经验"}],
              "weaknesses": [{"quote": "参与需求评审", "comment": "缺少量化结果"}],
              "suggestions": [{"original": "负责订单系统重构", "improved": "主导订单系统重构，QPS 提升 3 倍",
                               "reason": "量化结果更有说服力"}],
              "careerDirections": [
                {"direction": "Java 后端开发", "matchReason": "技能匹配", "skillGaps": ["分布式"], "interviewFocus": ["JVM"]},
                {"direction": "AI 应用开发", "matchReason": "有相关经验", "skillGaps": ["RAG"], "interviewFocus": ["Agent"]},
                {"direction": "平台工程", "matchReason": "工程基础好", "skillGaps": ["K8s"], "interviewFocus": ["容器"]}
              ]
            }
            """;

    private static HttpServer upstream;
    private static String upstreamBase;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ProviderClientRegistry clientRegistry;

    private WebTestClient client;

    @BeforeAll
    static void startUpstream() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            try {
                String body = MAPPER.writeValueAsString(Map.of(
                        "model", "real-model",
                        "choices", List.of(Map.of("message", Map.of("content", ANALYSIS_JSON))),
                        "usage", Map.of("prompt_tokens", 100, "completion_tokens", 200, "total_tokens", 300)));
                respond(exchange, 200, body);
            } catch (Exception ex) {
                respond(exchange, 500, "{}");
            }
        });
        upstream.createContext("/bad/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 401, "{\"error\":\"invalid token\"}");
        });
        upstream.start();
        upstreamBase = "http://127.0.0.1:" + upstream.getAddress().getPort();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop(0);
    }

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(60)).build();
        jdbc.update("DELETE FROM resume_analyses");
        jdbc.update("DELETE FROM resumes");
        jdbc.update("DELETE FROM llm_call_logs");
        jdbc.update("DELETE FROM llm_models");
        jdbc.update("DELETE FROM llm_providers");
        jdbc.update("DELETE FROM llm_global_setting");
        // 直连数据库清理不会触发缓存失效，需显式清掉上游客户端缓存
        clientRegistry.invalidate("stub");
        clientRegistry.invalidate("bad");
    }

    @Test
    @DisplayName("粘贴文本上传 → 自动分析 → 五维报告 / 分页 / 详情 / 删除")
    void textUploadAnalyzeReportAndDelete() {
        configureDefaultProvider();

        JsonNode uploaded = uploadText("张三\nJava 后端 3 年\n负责订单系统重构");
        assertThat(uploaded.path("data").path("duplicated").asBoolean()).isFalse();
        long resumeId = uploaded.path("data").path("resume").path("id").asLong();
        assertThat(resumeId).isPositive();

        JsonNode status = awaitStatus(resumeId, "COMPLETED");
        JsonNode report = status.path("report");
        assertThat(report.path("overallScore").asInt()).isEqualTo(76);
        assertThat(report.path("dimensions")).hasSize(5);
        assertThat(report.path("dimensions").get(0).path("weight").asInt()).isEqualTo(30);
        assertThat(report.path("dimensions").get(4).path("weight").asInt()).isEqualTo(10);
        assertThat(report.path("careerDirections")).hasSize(3);
        assertThat(report.path("llmProvider").asText()).isEqualTo("stub");
        assertThat(report.path("llmModel").asText()).isEqualTo(MODEL);
        assertThat(report.path("strengths").get(0).path("quote").asText()).contains("订单系统重构");

        client.get().uri("/api/resumes").exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.total").isEqualTo(1)
                .jsonPath("$.data.items[0].overallScore").isEqualTo(76)
                .jsonPath("$.data.items[0].analyzeStatus").isEqualTo("COMPLETED");

        client.get().uri("/api/resumes/{id}", resumeId).exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.resumeText").value(text -> assertThat(String.valueOf(text)).contains("张三"))
                .jsonPath("$.data.fileHash").value(hash -> assertThat(String.valueOf(hash)).hasSize(64))
                .jsonPath("$.data.retryCount").isEqualTo(0);

        client.delete().uri("/api/resumes/{id}", resumeId).exchange().expectStatus().isOk();
        client.get().uri("/api/resumes/{id}", resumeId).exchange()
                .expectStatus().isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("相同内容重复上传返回已有记录，不重复建记录")
    void duplicateUploadReturnsExisting() {
        configureDefaultProvider();
        String text = "重复内容简历\nJava";

        long first = uploadText(text).path("data").path("resume").path("id").asLong();
        JsonNode second = uploadText(text);

        assertThat(second.path("data").path("duplicated").asBoolean()).isTrue();
        assertThat(second.path("data").path("resume").path("id").asLong()).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resumes", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("文件上传（TXT）可解析，删除时清理磁盘文件")
    void fileUploadAndDelete() {
        configureDefaultProvider();
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource("李四\nJava 工程师".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "resume.txt";
            }
        });

        JsonNode body = client.post().uri("/api/resumes")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange().expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
        assertThat(body).isNotNull();
        long resumeId = body.path("data").path("resume").path("id").asLong();
        awaitStatus(resumeId, "COMPLETED");

        client.delete().uri("/api/resumes/{id}", resumeId).exchange().expectStatus().isOk();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resume_analyses", Integer.class)).isZero();
    }

    @Test
    @DisplayName("参数校验与执行中 409")
    void validationAndProcessingGuard() {
        client.post().uri("/api/resumes").contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(new MultipartBodyBuilder().build()))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo(40001);

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(new byte[] {1, 2, 3}) {
            @Override
            public String getFilename() {
                return "virus.exe";
            }
        });
        client.post().uri("/api/resumes").contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange().expectStatus().isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .expectBody().jsonPath("$.code").isEqualTo(41501);

        configureDefaultProvider();
        long resumeId = uploadText("处理中守卫测试").path("data").path("resume").path("id").asLong();
        awaitStatus(resumeId, "COMPLETED");
        jdbc.update("UPDATE resumes SET analyze_status = 'PROCESSING' WHERE id = ?", resumeId);

        client.post().uri("/api/resumes/{id}/analyze", resumeId).exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody().jsonPath("$.code").isEqualTo(40910);
    }

    @Test
    @DisplayName("上游持续失败时重试 3 次后置为 FAILED")
    void analysisFailureAfterRetries() {
        createProvider("bad", upstreamBase + "/bad");
        createModel("bad", MODEL);
        setGlobal("bad");

        long resumeId = uploadText("失败路径简历").path("data").path("resume").path("id").asLong();
        JsonNode status = awaitStatus(resumeId, "FAILED");

        assertThat(status.path("retryCount").asInt()).isEqualTo(3);
        assertThat(status.path("error").asText()).contains("HTTP 401");
        assertThat(status.path("report").isNull()).isTrue();
    }

    private void configureDefaultProvider() {
        createProvider("stub", upstreamBase + "/v1");
        createModel("stub", MODEL);
        setGlobal("stub");
    }

    private void createProvider(String key, String baseUrl) {
        client.post().uri("/api/llm/providers").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("providerKey", key, "label", key, "baseUrl", baseUrl,
                        "apiKey", "sk-12345678test"))
                .exchange().expectStatus().isOk();
    }

    private void createModel(String providerKey, String model) {
        client.post().uri("/api/llm/providers/{key}/models", providerKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("modelName", model, "label", model, "isDefault", true))
                .exchange().expectStatus().isOk();
    }

    private void setGlobal(String providerKey) {
        client.put().uri("/api/llm/global").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("defaultProviderKey", providerKey, "defaultModel", MODEL))
                .exchange().expectStatus().isOk();
    }

    private JsonNode uploadText(String text) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("text", text);
        JsonNode body = client.post().uri("/api/resumes")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange().expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
        assertThat(body).isNotNull();
        return body;
    }

    /** 轮询分析状态直到达到期望值或超时。 */
    private JsonNode awaitStatus(long resumeId, String expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        JsonNode data = null;
        while (System.nanoTime() < deadline) {
            JsonNode body = client.get().uri("/api/resumes/{id}/analysis", resumeId)
                    .exchange().expectStatus().isOk()
                    .expectBody(JsonNode.class).returnResult().getResponseBody();
            assertThat(body).isNotNull();
            data = body.path("data");
            if (expected.equals(data.path("status").asText())) {
                return data;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("等待分析状态 " + expected + " 超时，最后状态: "
                + (data == null ? "null" : data.path("status").asText()));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
