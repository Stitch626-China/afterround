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
import org.junit.jupiter.api.condition.EnabledIf;
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
 * M2 第一批端到端集成测试：真实 PostgreSQL + 本地假 LLM 上游。
 *
 * <p>覆盖 JD 导入与异步解析、公司自动关联、场景绑定、简历-JD 匹配、行情洞察、
 * 简历对比，以及 HTTP 层校验、执行中 409、删除级联与上游 401 重试打满。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIf("com.jobpilot.AbstractIntegrationTest#dockerAvailable")
class M2Batch1ApiIntegrationTest extends AbstractIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MODEL = "Qwen3.8-27B";
    private static final String JD_JSON = """
            {
              "title": "Java 后端开发工程师", "companyName": "字节跳动", "city": "上海",
              "salaryMin": 20000, "salaryMax": 30000, "experience": "3-5年", "education": "本科及以上",
              "mustHaveSkills": ["Java", "Redis"], "niceToHaveSkills": ["Kafka"],
              "responsibility": "负责订单服务开发"
            }
            """;
    private static final String MATCH_JSON = """
            {
              "skillMatch": 80, "experienceMatch": 70, "bonusCoverage": 60,
              "gaps": ["缺少 Kafka 实战"], "preparationAdvice": ["补充消息队列项目"]
            }
            """;
    private static final String COMPARISON_JSON = "{\"summary\":\"新版简历技术深度更高，建议补充量化结果。\"}";
    private static final String ANALYSIS_JSON = """
            {
              "technicalDepth": 82, "projectQuality": 75, "engineeringAbility": 70,
              "expressionStructure": 65, "jobMatchPotential": 90,
              "basicInfo": {"name": "张三", "yearsOfExperience": 3, "techDirection": "Java 后端",
                            "currentMonthlySalary": 30000, "expectedMonthlySalary": 40000},
              "strengths": [{"quote": "负责订单系统重构", "comment": "有主导经验"}],
              "weaknesses": [{"quote": "参与需求评审", "comment": "缺少量化结果"}],
              "suggestions": [],
              "careerDirections": [
                {"direction": "Java 后端开发", "matchReason": "技能匹配", "skillGaps": [], "interviewFocus": []}
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
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            try {
                // 特征串取各 system prompt 的唯一措辞：简历分析 prompt 也含「匹配度」，
                // 不能用它区分，否则简历分析会拿到匹配 JSON 而结构化失败
                if (body.contains("JD 结构化解析")) {
                    respond(exchange, 200, result(JD_JSON));
                } else if (body.contains("两版简历")) {
                    respond(exchange, 200, result(COMPARISON_JSON));
                } else if (body.contains("岗位 JD 的匹配度")) {
                    respond(exchange, 200, result(MATCH_JSON));
                } else {
                    respond(exchange, 200, result(ANALYSIS_JSON));
                }
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
        jdbc.update("DELETE FROM job_match_results");
        jdbc.update("DELETE FROM job_positions");
        jdbc.update("DELETE FROM companies");
        jdbc.update("DELETE FROM llm_scene_settings");
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
    @DisplayName("导入 JD → 解析 → 公司关联 → 场景绑定 → 匹配 → 行情 → 对比 → 删除级联")
    void fullPipeline() {
        configureDefaultProvider();
        long jobId = importJd("字节跳动 Java 后端\n20-30K\n3-5年\n必备：Java、Redis");
        assertThat(jobId).isPositive();
        JsonNode job = awaitJob(jobId, "COMPLETED");
        assertThat(job.path("title").asText()).isEqualTo("Java 后端开发工程师");
        assertThat(job.path("city").asText()).isEqualTo("上海");
        assertThat(job.path("salaryMin").asInt()).isEqualTo(20000);
        assertThat(job.path("salaryMax").asInt()).isEqualTo(30000);
        assertThat(job.path("companyName").asText()).isEqualTo("字节跳动");
        assertThat(job.path("parseError").isNull()).isTrue();
        assertThat(job.path("llmProvider").asText()).isEqualTo("stub");
        assertThat(job.path("mustHaveSkills")).hasSize(2);
        long companyId = job.path("companyId").asLong();
        assertThat(companyId).isPositive();

        // 公司联想检索与岗位计数
        JsonNode companies = getJson(uri -> uri.path("/api/companies").queryParam("keyword", "字节").build());
        assertThat(companies.path("data").path("total").asInt()).isEqualTo(1);
        assertThat(companies.path("data").path("items").get(0).path("jobPositionCount").asInt()).isEqualTo(1);

        // 场景绑定：默认回落全局，绑定后生效，解绑再次回落
        JsonNode scenes = getJson("/api/llm/scenes").path("data");
        assertThat(scenes).hasSize(7);
        JsonNode chatScene = findScene(scenes, "CHAT");
        assertThat(chatScene.path("inherited").asBoolean()).isTrue();
        client.put().uri("/api/llm/scenes/JOB_MATCH").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("providerKey", "stub", "model", MODEL))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.inherited").isEqualTo(false)
                .jsonPath("$.data.effectiveProviderKey").isEqualTo("stub");
        client.delete().uri("/api/llm/scenes/JOB_MATCH").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.inherited").isEqualTo(true);

        // 简历分析完成后再发起匹配
        long resumeId = uploadResume("张三\nJava 后端 3 年\n负责订单系统重构");
        awaitResume(resumeId, "COMPLETED");
        JsonNode match = postJson("/api/job-matches",
                Map.of("resumeId", resumeId, "jobPositionId", jobId)).path("data");
        long matchId = match.path("id").asLong();
        assertThat(matchId).isPositive();
        JsonNode settled = awaitMatch(matchId, "COMPLETED");
        assertThat(settled.path("overallScore").asInt()).isEqualTo(73);
        assertThat(settled.path("detail").path("dimensions")).hasSize(3);
        assertThat(settled.path("detail").path("dimensions").get(0).path("weight").asInt()).isEqualTo(50);
        assertThat(settled.path("detail").path("dimensions").get(1).path("weight").asInt()).isEqualTo(30);
        assertThat(settled.path("detail").path("dimensions").get(2).path("weight").asInt()).isEqualTo(20);
        assertThat(settled.path("detail").path("gaps").get(0).asText()).contains("Kafka");

        JsonNode matches = getJson("/api/job-matches?resumeId=" + resumeId + "&jobPositionId=" + jobId);
        assertThat(matches.path("data").path("total").asInt()).isEqualTo(1);

        // 行情洞察：只统计解析完成的 JD
        JsonNode insights = getJson("/api/job-market/insights").path("data");
        assertThat(insights.path("sampleSize").asInt()).isEqualTo(1);
        assertThat(insights.path("skillTop").toString()).contains("java").contains("redis").contains("kafka");
        assertThat(insights.path("salaryDistribution").get(2).path("count").asInt()).isEqualTo(1);
        assertThat(insights.path("directionDistribution").get(0).path("label").asText())
                .isEqualTo("Java 后端开发工程师");

        // 简历对比：两份都完成后同步返回，LLM 总结不降级
        long secondResumeId = uploadResume("李四\nJava 工程师 2 年\n参与订单系统");
        awaitResume(secondResumeId, "COMPLETED");
        JsonNode comparison = postJson("/api/resume-comparisons",
                Map.of("baseResumeId", resumeId, "targetResumeId", secondResumeId)).path("data");
        assertThat(comparison.path("dimensionDeltas")).hasSize(5);
        assertThat(comparison.path("overallScoreDelta").asInt()).isZero();
        assertThat(comparison.path("summary").path("degraded").asBoolean()).isFalse();
        assertThat(comparison.path("summary").path("content").asText()).contains("技术深度");

        // 删除岗位：详情 404，匹配结果级联删除，公司解除引用后可删
        client.delete().uri("/api/job-positions/{id}", jobId).exchange().expectStatus().isOk();
        client.get().uri("/api/job-positions/{id}", jobId).exchange()
                .expectStatus().isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_match_results", Integer.class)).isZero();
        client.delete().uri("/api/companies/{id}", companyId).exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("HTTP 层校验：空导入 / 非法文件 / 重名公司 / 被引用公司 / 对比前置条件")
    void validationsAndGuards() {
        client.post().uri("/api/job-positions").contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(new MultipartBodyBuilder().build()))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo(40001);

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(new byte[] {1, 2, 3}) {
            @Override
            public String getFilename() {
                return "jd.exe";
            }
        });
        client.post().uri("/api/job-positions").contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange().expectStatus().isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .expectBody().jsonPath("$.code").isEqualTo(41501);

        postJson("/api/companies", Map.of("name", "字节跳动"));
        client.post().uri("/api/companies").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "字节跳动"))
                .exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody().jsonPath("$.code").isEqualTo(40906);

        configureDefaultProvider();
        long jobId = importJd("被引用公司测试 JD");
        JsonNode job = awaitJob(jobId, "COMPLETED");
        long companyId = job.path("companyId").asLong();
        client.delete().uri("/api/companies/{id}", companyId).exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody().jsonPath("$.code").isEqualTo(40905);

        client.post().uri("/api/job-matches").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("resumeId", 9999, "jobPositionId", jobId))
                .exchange().expectStatus().isEqualTo(HttpStatus.NOT_FOUND)
                .expectBody().jsonPath("$.code").isEqualTo(40401);

        long resumeId = uploadResume("张三\nJava 3 年");
        awaitResume(resumeId, "COMPLETED");
        client.post().uri("/api/resume-comparisons").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("baseResumeId", resumeId, "targetResumeId", resumeId))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo(40001);

        // 分析未完成的简历不能参与对比：两份都必须存在，其中一份置为 PENDING
        long completedResumeId = uploadResume("李四\nJava 4 年");
        awaitResume(completedResumeId, "COMPLETED");
        jdbc.update("UPDATE resumes SET analyze_status = 'PENDING' WHERE id = ?", resumeId);
        client.post().uri("/api/resume-comparisons").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("baseResumeId", resumeId, "targetResumeId", completedResumeId))
                .exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody().jsonPath("$.code").isEqualTo(40907);

        // 匹配执行中重复发起返回 409，允许重跑
        long secondResumeId = uploadResume("王五\nJava 5 年");
        awaitResume(secondResumeId, "COMPLETED");
        long matchId = postJson("/api/job-matches",
                Map.of("resumeId", secondResumeId, "jobPositionId", jobId)).path("data").path("id").asLong();
        awaitMatch(matchId, "COMPLETED");
        jdbc.update("UPDATE job_match_results SET status = 'PROCESSING' WHERE id = ?", matchId);
        client.post().uri("/api/job-matches").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("resumeId", secondResumeId, "jobPositionId", jobId))
                .exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody().jsonPath("$.code").isEqualTo(40908);
        jdbc.update("UPDATE job_match_results SET status = 'FAILED', retry_count = 3 WHERE id = ?", matchId);
        JsonNode rerun = postJson("/api/job-matches",
                Map.of("resumeId", secondResumeId, "jobPositionId", jobId)).path("data");
        assertThat(rerun.path("id").asLong()).isEqualTo(matchId);
        JsonNode settled = awaitMatch(matchId, "COMPLETED");
        assertThat(settled.path("retryCount").asInt()).isZero();
    }

    @Test
    @DisplayName("上游持续 401 时 JD 解析重试 3 次后置为 FAILED")
    void parseFailsAfterRetries() {
        createProvider("bad", upstreamBase + "/bad");
        createModel("bad", MODEL);
        setGlobal("bad");

        long jobId = importJd("失败路径 JD\nJava");
        JsonNode job = awaitJob(jobId, "FAILED");

        assertThat(job.path("retryCount").asInt()).isEqualTo(3);
        assertThat(job.path("parseError").asText()).contains("HTTP 401");
        assertThat(job.path("title").isNull()).isTrue();
    }

    private static JsonNode findScene(JsonNode scenes, String scene) {
        for (JsonNode item : scenes) {
            if (scene.equals(item.path("scene").asText())) {
                return item;
            }
        }
        throw new AssertionError("场景未返回: " + scene);
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

    private long importJd(String text) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("text", text);
        JsonNode body = client.post().uri("/api/job-positions")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange().expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
        assertThat(body).isNotNull();
        return body.path("data").path("id").asLong();
    }

    private long uploadResume(String text) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("text", text);
        JsonNode body = client.post().uri("/api/resumes")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange().expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
        assertThat(body).isNotNull();
        return body.path("data").path("resume").path("id").asLong();
    }

    private JsonNode getJson(String uri) {
        JsonNode body = client.get().uri(uri).exchange().expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
        assertThat(body).isNotNull();
        return body;
    }

    /** 带查询参数的 GET（由 WebTestClient 负责中文编码）。 */
    private JsonNode getJson(java.util.function.Function<org.springframework.web.util.UriBuilder,
            java.net.URI> uriFunction) {
        JsonNode body = client.get().uri(uriFunction).exchange().expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
        assertThat(body).isNotNull();
        return body;
    }

    private JsonNode postJson(String uri, Map<String, Object> payload) {
        JsonNode body = client.post().uri(uri).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload).exchange().expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
        assertThat(body).isNotNull();
        return body;
    }

    private JsonNode awaitJob(long jobId, String expected) {
        return await("job-positions/" + jobId, "parseStatus", expected, "岗位 " + jobId);
    }

    private JsonNode awaitMatch(long matchId, String expected) {
        return await("job-matches/" + matchId, "status", expected, "匹配 " + matchId);
    }

    private JsonNode awaitResume(long resumeId, String expected) {
        return await("resumes/" + resumeId + "/analysis", "status", expected, "简历 " + resumeId);
    }

    /** 轮询指定端点直到状态字段达到期望值或超时。 */
    private JsonNode await(String path, String field, String expected, String label) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        JsonNode data = null;
        while (System.nanoTime() < deadline) {
            JsonNode body = client.get().uri("/api/" + path).exchange().expectStatus().isOk()
                    .expectBody(JsonNode.class).returnResult().getResponseBody();
            assertThat(body).isNotNull();
            data = body.path("data");
            if (expected.equals(data.path(field).asText())) {
                return data;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("等待 " + label + " 状态 " + expected + " 超时，最后状态: "
                + (data == null ? "null" : data.path(field).asText()));
    }

    private static String result(String content) throws Exception {
        return MAPPER.writeValueAsString(Map.of(
                "model", "real-model",
                "choices", List.of(Map.of("message", Map.of("content", content))),
                "usage", Map.of("prompt_tokens", 100, "completion_tokens", 200, "total_tokens", 300)));
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
