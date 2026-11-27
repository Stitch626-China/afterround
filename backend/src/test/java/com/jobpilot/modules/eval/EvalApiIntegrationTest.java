package com.jobpilot.modules.eval;

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
import com.jobpilot.modules.eval.service.EvalClientFactory;
import com.jobpilot.modules.eval.service.EvalRunRecovery;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 评测运行 5 端点集成测试：真实 PostgreSQL + 假上游。
 *
 * <p>覆盖创建快照、异步执行到 COMPLETED、并发运行 40918、取消、已结束 40919 与启动恢复。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIf("com.jobpilot.AbstractIntegrationTest#dockerAvailable")
class EvalApiIntegrationTest extends AbstractIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MODEL = "Qwen3.8-27B";
    private static final String BASE = "/api/llm/eval";
    private static final String ANALYSIS_JSON = """
            {
              "technicalDepth": 82, "projectQuality": 75, "engineeringAbility": 70,
              "expressionStructure": 65, "jobMatchPotential": 90,
              "basicInfo": {"name": "张三", "yearsOfExperience": 3, "techDirection": "Java 后端",
                            "currentMonthlySalary": 30000, "expectedMonthlySalary": 40000},
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
    private ProviderClientRegistry providerRegistry;

    @Autowired
    private EvalClientFactory evalClientFactory;

    @Autowired
    private EvalRunRecovery recovery;

    private WebTestClient client;

    @BeforeAll
    static void startUpstream() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            try {
                String body = MAPPER.writeValueAsString(Map.of(
                        "model", MODEL,
                        "choices", List.of(Map.of("message", Map.of("content", ANALYSIS_JSON))),
                        "usage", Map.of("prompt_tokens", 100, "completion_tokens", 200, "total_tokens", 300)));
                respond(exchange, 200, body);
            } catch (Exception ex) {
                respond(exchange, 500, "{}");
            }
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
        jdbc.update("DELETE FROM llm_eval_baselines");
        jdbc.update("DELETE FROM llm_eval_run_cases");
        jdbc.update("DELETE FROM llm_eval_runs");
        jdbc.update("DELETE FROM llm_call_logs");
        jdbc.update("DELETE FROM llm_models");
        jdbc.update("DELETE FROM llm_providers");
        jdbc.update("DELETE FROM llm_global_setting");
        providerRegistry.invalidate("stub");
        evalClientFactory.invalidate("stub");
    }

    @Test
    @DisplayName("创建运行 → 异步跑完简历 10 例 → 指标 / 快照 / 调用埋点可查")
    void createsRunAndCompletesResumeScene() throws Exception {
        configureDefaultProvider();

        String raw = client.post().uri(BASE + "/runs").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("scenes", List.of("resume-analysis"), "repeat", 1))
                .exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        JsonNode created = MAPPER.readTree(raw);
        assertThat(created.path("data").path("status").asText()).isEqualTo("PENDING");
        assertThat(created.path("data").path("totalCases").asInt()).isEqualTo(10);
        assertThat(created.path("data").path("models").get(0).path("providerKey").asText()).isEqualTo("stub");
        assertThat(created.path("data").path("prompts").get(0).path("promptVersion").asText())
                .isEqualTo("resume-analysis-v3");

        long runId = created.path("data").path("id").asLong();
        JsonNode detail = awaitTerminal(runId).path("data");
        assertThat(detail.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(detail.path("completedCases").asInt()).isEqualTo(10);
        assertThat(detail.path("errorCases").asInt()).isZero();
        assertThat(detail.path("cases")).hasSize(10);
        assertThat(detail.path("metrics").path("l1Total").isNull()).isFalse();
        assertThat(detail.path("metrics").path("totalTokens").asInt()).isEqualTo(3000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM llm_call_logs WHERE scene = 'EVAL_RESUME_ANALYZE'",
                Integer.class)).isEqualTo(10);

        client.get().uri(BASE + "/runs?page=1&size=20").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.total").isEqualTo(1)
                .jsonPath("$.data.items[0].id").isEqualTo(runId);

        client.get().uri(BASE + "/scenes").exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(3)
                .jsonPath("$.data[0].caseCount").isEqualTo(10)
                .jsonPath("$.data[0].providerKey").isEqualTo("stub")
                .jsonPath("$.data[0].model").isEqualTo(MODEL);
    }

    @Test
    @DisplayName("已有活跃运行 → 40918；取消 PENDING → CANCELLED；再取消 → 40919")
    void rejectsConcurrentRunAndCancelsPending() {
        long runId = jdbc.queryForObject("""
                INSERT INTO llm_eval_runs (status, scenes, model_snapshot, prompt_snapshot,
                    corpus_hash, engine_version, params, total_cases)
                VALUES ('PENDING', '["resume-analysis"]'::jsonb, '[]'::jsonb, '[]'::jsonb,
                    'h', 'llm-eval-v1', '{}'::jsonb, 0) RETURNING id
                """, Long.class);

        client.post().uri(BASE + "/runs").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("scenes", List.of("resume-analysis")))
                .exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo(40918);

        client.post().uri(BASE + "/runs/{id}/cancel", runId).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.status").isEqualTo("CANCELLED");

        client.post().uri(BASE + "/runs/{id}/cancel", runId).exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo(40919);
    }

    @Test
    @DisplayName("启动恢复：PROCESSING 运行置 FAILED，剩余 PENDING 用例置 SKIPPED")
    void recoveryFailsInterruptedRuns() {
        long runId = jdbc.queryForObject("""
                INSERT INTO llm_eval_runs (status, scenes, model_snapshot, prompt_snapshot,
                    corpus_hash, engine_version, params, total_cases, started_at)
                VALUES ('PROCESSING', '["jd-parse"]'::jsonb, '[]'::jsonb, '[]'::jsonb,
                    'h', 'llm-eval-v1', '{}'::jsonb, 1, now()) RETURNING id
                """, Long.class);
        jdbc.update("""
                INSERT INTO llm_eval_run_cases (run_id, scene, case_id, status)
                VALUES (?, 'jd-parse', '001-java-python-ai-backend', 'PENDING')
                """, runId);

        recovery.run(null);

        assertThat(jdbc.queryForObject("SELECT status FROM llm_eval_runs WHERE id = ?", String.class, runId))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM llm_eval_run_cases WHERE run_id = ?",
                String.class, runId)).isEqualTo("SKIPPED");
    }

    private void configureDefaultProvider() {
        client.post().uri("/api/llm/providers").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("providerKey", "stub", "label", "stub", "baseUrl", upstreamBase + "/v1",
                        "apiKey", "sk-12345678test"))
                .exchange().expectStatus().isOk();
        client.post().uri("/api/llm/providers/stub/models").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("modelName", MODEL, "label", MODEL, "isDefault", true))
                .exchange().expectStatus().isOk();
        client.put().uri("/api/llm/global").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("defaultProviderKey", "stub", "defaultModel", MODEL))
                .exchange().expectStatus().isOk();
    }

    private JsonNode awaitTerminal(long runId) throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            JsonNode body = client.get().uri(BASE + "/runs/" + runId).exchange().expectStatus().isOk()
                    .expectBody(JsonNode.class).returnResult().getResponseBody();
            String status = body == null ? "" : body.path("data").path("status").asText();
            if (List.of("COMPLETED", "FAILED", "CANCELLED").contains(status)) {
                return body;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("评测运行未在时限内结束");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
