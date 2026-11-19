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
 * M2 第二批端到端集成测试：真实 PostgreSQL + 本地假 LLM 上游。
 *
 * <p>覆盖模拟面试（出题 / 追问 / 跳过 / 交卷 / 评估 / 切换 / 重试 / 删除）与
 * Offer 决策（CRUD / 保留组上限 / 固定行对比 / 文本与图片提取 / 前景分析）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIf("com.jobpilot.AbstractIntegrationTest#dockerAvailable")
class M2Batch2ApiIntegrationTest extends AbstractIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MODEL = "Qwen3.8-27B";
    private static final String QUESTIONS_JSON = questions(8);
    private static final String FOLLOW_UP_JSON = """
            {"questions":[{"content":"追问：这个方案在流量翻倍时最先出现什么问题？",
              "topicSummary":"容量评估","difficulty":"HARD","keyPoints":["瓶颈定位"],"referenceAnswer":"参考追问"}]}
            """;
    private static final String EVALUATION_JSON = """
            {
              "questions": [
                {"questionIndex": 1, "score": 80, "feedback": "回答覆盖了核心链路", "referenceAnswer": "参考 1"},
                {"questionIndex": 2, "score": 80, "feedback": "追问回答到位", "referenceAnswer": "参考 2"},
                {"questionIndex": 3, "score": 70, "feedback": "已跳过", "referenceAnswer": "参考 3"},
                {"questionIndex": 4, "score": 80, "feedback": "边界考虑完整", "referenceAnswer": "参考 4"},
                {"questionIndex": 5, "score": 60, "feedback": "未作答", "referenceAnswer": "参考 5"},
                {"questionIndex": 6, "score": 60, "feedback": "未作答", "referenceAnswer": "参考 6"},
                {"questionIndex": 7, "score": 60, "feedback": "未作答", "referenceAnswer": "参考 7"},
                {"questionIndex": 8, "score": 60, "feedback": "未作答", "referenceAnswer": "参考 8"},
                {"questionIndex": 9, "score": 60, "feedback": "未作答", "referenceAnswer": "参考 9"}
              ],
              "categories": [
                {"key": "java-basics", "label": "Java 基础", "score": 80},
                {"key": "concurrency-jvm", "label": "并发与 JVM", "score": 80},
                {"key": "spring-framework", "label": "Spring 与框架", "score": 60},
                {"key": "database-cache", "label": "数据库与缓存", "score": 60},
                {"key": "distributed-system", "label": "分布式与工程实践", "score": 60}
              ],
              "strengths": ["回答结构清晰"],
              "improvements": ["补充量化数据"],
              "summary": "整体达到中级后端水平，建议加强分布式经验。"
            }
            """;
    private static final String EXTRACT_JSON = """
            {
              "companyName": "字节跳动", "jobPositionTitle": "Java 后端开发工程师",
              "monthlySalary": 30000, "baseSalary": 24000, "performanceRatio": 20,
              "socialInsuranceBase": "12000", "socialInsuranceRatio": "10.5%",
              "housingFundBase": "12000", "housingFundRatio": "12%",
              "twoDayWeekend": true, "workHours": "10:00-19:00",
              "benefits": ["餐补", "免费班车"], "allowances": ["房补"],
              "annualBonus": "3 个月", "quarterlyBonus": "0.5 个月",
              "remark": "试用期 3 个月"
            }
            """;
    private static final String ANALYSIS_JSON = """
            {
              "tendency": "ACCEPT", "reasons": ["薪资高于同城同级别", "技术栈匹配"],
              "prospect": "业务稳定，后端团队仍在扩张。", "risks": ["绩效占比需要确认考核口径"],
              "sources": ["公司官网招聘页"]
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
                if (body.contains("追问要求")) {
                    respond(exchange, 200, result(FOLLOW_UP_JSON));
                } else if (body.contains("出题要求")) {
                    respond(exchange, 200, result(QUESTIONS_JSON));
                } else if (body.contains("评估要求")) {
                    respond(exchange, 200, result(EVALUATION_JSON));
                } else if (body.contains("提取结构化字段")) {
                    respond(exchange, 200, result(EXTRACT_JSON));
                } else if (body.contains("评估一个 Offer 的前景")) {
                    respond(exchange, 200, result(ANALYSIS_JSON));
                } else {
                    respond(exchange, 200, result("{\"summary\":\"ok\"}"));
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
        jdbc.update("DELETE FROM interview_questions");
        jdbc.update("DELETE FROM interview_sessions");
        jdbc.update("DELETE FROM offers");
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
        clientRegistry.invalidate("stub");
        clientRegistry.invalidate("bad");
    }

    @Test
    @DisplayName("面试全链路：出题 → 追问 → 跳过 → 切换模型 → 交卷 → 评估 → 删除")
    void interviewPipeline() {
        configureDefaultProvider();
        JsonNode directions = getJson("/api/interview/directions").path("data");
        assertThat(directions).hasSize(2);
        assertThat(directions.get(0).path("key").asText()).isEqualTo("java-backend");

        JsonNode created = postJson("/api/interview-sessions",
                Map.of("direction", "java-backend", "questionCount", 8, "followUpCount", 1)).path("data");
        long sessionId = created.path("id").asLong();
        assertThat(created.path("status").asText()).isEqualTo("PREPARING");
        JsonNode detail = awaitSession(sessionId, "IN_PROGRESS");
        assertThat(detail.path("questions")).hasSize(8);
        assertThat(detail.path("questions").get(0).path("type").asText()).isEqualTo("MAIN");
        assertThat(detail.path("questions").get(0).path("llmProvider").asText()).isEqualTo("stub");
        String firstTopic = detail.path("questions").get(0).path("topicSummary").asText();
        assertThat(firstTopic).isNotBlank();

        // 主问题作答 → 追问插入到紧随其后，后续题号顺延
        JsonNode answered = postJson("/api/interview-sessions/" + sessionId + "/questions/1/answer",
                Map.of("answer", "我负责订单系统重构，用 Redis 做了缓存一致性。")).path("data");
        assertThat(answered.path("answerStatus").asText()).isEqualTo("ANSWERED");
        detail = awaitSession(sessionId, "IN_PROGRESS");
        assertThat(detail.path("questions")).hasSize(9);
        JsonNode followUp = detail.path("questions").get(1);
        assertThat(followUp.path("type").asText()).isEqualTo("FOLLOW_UP");
        assertThat(followUp.path("parentIndex").asInt()).isEqualTo(1);
        assertThat(detail.path("questions").get(2).path("questionIndex").asInt()).isEqualTo(3);
        assertThat(detail.path("questions").get(2).path("type").asText()).isEqualTo("MAIN");

        postJson("/api/interview-sessions/" + sessionId + "/questions/2/answer",
                Map.of("answer", "先做限流，再看数据库连接池。"));
        JsonNode skipped = postJson("/api/interview-sessions/" + sessionId + "/questions/3/skip", Map.of())
                .path("data");
        assertThat(skipped.path("answerStatus").asText()).isEqualTo("SKIPPED");
        postJson("/api/interview-sessions/" + sessionId + "/questions/4/answer",
                Map.of("answer", "Spring 事务传播行为按业务边界选择。"));
        awaitSession(sessionId, "IN_PROGRESS");

        // 会话内两级切换：IN_PROGRESS 允许
        JsonNode switched = patchJson("/api/interview-sessions/" + sessionId + "/model",
                Map.of("providerKey", "stub", "model", MODEL)).path("data");
        assertThat(switched.path("currentModel").asText()).isEqualTo(MODEL);
        assertThat(switched.path("answeredCount").asInt()).isEqualTo(4);

        // 列表筛选
        assertThat(getJson("/api/interview-sessions?status=IN_PROGRESS&direction=java-backend")
                .path("data").path("total").asInt()).isEqualTo(1);

        // 交卷 → 评估完成
        JsonNode submitted = postJson("/api/interview-sessions/" + sessionId + "/submit", Map.of()).path("data");
        assertThat(submitted.path("status").asText()).isEqualTo("SUBMITTED");
        JsonNode evaluation = awaitEvaluation(sessionId, "COMPLETED");
        assertThat(evaluation.path("overallScore").asInt()).isEqualTo(69);
        assertThat(evaluation.path("categories")).hasSize(5);
        assertThat(evaluation.path("strengths").get(0).asText()).contains("结构清晰");
        assertThat(evaluation.path("summary").asText()).contains("中级后端");
        assertThat(evaluation.path("llmProvider").asText()).isEqualTo("stub");

        detail = awaitSession(sessionId, "COMPLETED");
        JsonNode questions = detail.path("questions");
        assertThat(questions.get(0).path("score").asInt()).isEqualTo(80);
        assertThat(questions.get(0).path("feedback").asText()).contains("核心链路");
        assertThat(questions.get(2).path("answerStatus").asText()).isEqualTo("SKIPPED");
        assertThat(questions.get(2).path("score").asInt()).isZero();
        assertThat(questions.get(2).path("feedback").isNull()).isTrue();
        assertThat(questions.get(4).path("answerStatus").asText()).isEqualTo("UNANSWERED");
        assertThat(questions.get(4).path("score").asInt()).isZero();

        // 终态守卫：作答 40912、切换 40902、重复交卷 40912
        request("/api/interview-sessions/" + sessionId + "/questions/5/answer", "POST",
                Map.of("answer", "x"), HttpStatus.CONFLICT, 40912);
        request("/api/interview-sessions/" + sessionId + "/model", "PATCH",
                Map.of("providerKey", "stub", "model", MODEL), HttpStatus.CONFLICT, 40902);
        request("/api/interview-sessions/" + sessionId + "/submit", "POST", Map.of(),
                HttpStatus.CONFLICT, 40912);

        // 删除级联：题目一并清理
        client.delete().uri("/api/interview-sessions/{id}", sessionId).exchange().expectStatus().isOk();
        client.get().uri("/api/interview-sessions/{id}", sessionId).exchange()
                .expectStatus().isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_questions", Integer.class)).isZero();
    }

    @Test
    @DisplayName("面试校验与状态守卫：方向/关联校验、执行中 409、失败重试、切换模型")
    void interviewGuards() {
        configureDefaultProvider();
        request("/api/interview-sessions", "POST", Map.of("questionCount", 8),
                HttpStatus.BAD_REQUEST, 40001);
        request("/api/interview-sessions", "POST", Map.of("direction", "nope"),
                HttpStatus.NOT_FOUND, 40401);
        request("/api/interview-sessions", "POST", Map.of("direction", "java-backend", "questionCount", 4),
                HttpStatus.BAD_REQUEST, 40001);
        request("/api/interview-sessions", "POST", Map.of("direction", "java-backend", "followUpCount", 4),
                HttpStatus.BAD_REQUEST, 40001);
        request("/api/interview-sessions", "POST", Map.of("direction", "java-backend", "resumeId", 9999),
                HttpStatus.NOT_FOUND, 40401);
        request("/api/interview-sessions", "POST", Map.of("direction", "java-backend", "jobPositionId", 9999),
                HttpStatus.NOT_FOUND, 40401);
        request("/api/interview-sessions", "POST", Map.of("direction", "java-backend", "providerKey", "stub"),
                HttpStatus.BAD_REQUEST, 40001);

        // 仅关联 JD：按 JD 关键词匹配方向，并回填岗位 / 公司展示字段
        long companyId = postJson("/api/companies", Map.of("name", "字节跳动")).path("data").path("id").asLong();
        long jobId = insertJob(companyId, "Java 后端开发工程师", "Java Spring JVM MySQL Redis 微服务");
        JsonNode byJob = postJson("/api/interview-sessions", Map.of("jobPositionId", jobId)).path("data");
        assertThat(byJob.path("direction").asText()).isEqualTo("java-backend");
        long jobSessionId = byJob.path("id").asLong();
        JsonNode jobDetail = awaitSession(jobSessionId, "IN_PROGRESS");
        assertThat(jobDetail.path("jobPositionTitle").asText()).isEqualTo("Java 后端开发工程师");
        assertThat(jobDetail.path("companyName").asText()).isEqualTo("字节跳动");

        // 状态守卫：GENERATING / SUBMITTED → 40901，COMPLETED → 40902，FAILED → 40913
        jdbc.update("UPDATE interview_sessions SET status = 'GENERATING' WHERE id = ?", jobSessionId);
        request("/api/interview-sessions/" + jobSessionId + "/model", "PATCH",
                Map.of("providerKey", "stub", "model", MODEL), HttpStatus.CONFLICT, 40901);
        jdbc.update("UPDATE interview_sessions SET status = 'SUBMITTED' WHERE id = ?", jobSessionId);
        request("/api/interview-sessions/" + jobSessionId + "/model", "PATCH",
                Map.of("providerKey", "stub", "model", MODEL), HttpStatus.CONFLICT, 40901);
        request("/api/interview-sessions/" + jobSessionId + "/questions/1/answer", "POST",
                Map.of("answer", "x"), HttpStatus.CONFLICT, 40911);
        jdbc.update("UPDATE interview_sessions SET status = 'COMPLETED' WHERE id = ?", jobSessionId);
        request("/api/interview-sessions/" + jobSessionId + "/questions/1/answer", "POST",
                Map.of("answer", "x"), HttpStatus.CONFLICT, 40912);
        jdbc.update("UPDATE interview_sessions SET status = 'FAILED' WHERE id = ?", jobSessionId);
        request("/api/interview-sessions/" + jobSessionId + "/questions/1/answer", "POST",
                Map.of("answer", "x"), HttpStatus.CONFLICT, 40913);
        request("/api/interview-sessions/" + jobSessionId + "/evaluation", "GET", null,
                HttpStatus.CONFLICT, 40913);

        // FAILED 下切成不可用模型 → 400；切回 stub 后重试出题成功
        request("/api/interview-sessions/" + jobSessionId + "/model", "PATCH",
                Map.of("providerKey", "missing", "model", MODEL), HttpStatus.BAD_REQUEST, 40001);
        patchJson("/api/interview-sessions/" + jobSessionId + "/model",
                Map.of("providerKey", "stub", "model", MODEL));
        JsonNode retried = postJson("/api/interview-sessions/" + jobSessionId + "/questions/retry", Map.of())
                .path("data");
        assertThat(retried.path("retryCount").asInt()).isZero();
        awaitSession(jobSessionId, "IN_PROGRESS");

        // 未交卷无评估；无题目不能交卷
        request("/api/interview-sessions/" + jobSessionId + "/evaluation", "GET", null,
                HttpStatus.CONFLICT, 40913);
        jdbc.update("INSERT INTO interview_sessions (user_id, direction, direction_label, status, "
                + "question_count, follow_up_count, answered_count, evaluate_status, evaluate_retry_count) "
                + "VALUES (1, 'java-backend', 'Java 后端', 'IN_PROGRESS', 8, 1, 0, 'PENDING', 0)");
        Long emptyId = jdbc.queryForObject("SELECT max(id) FROM interview_sessions", Long.class);
        request("/api/interview-sessions/" + emptyId + "/submit", "POST", Map.of(),
                HttpStatus.CONFLICT, 40913);

        client.delete().uri("/api/interview-sessions/{id}", jobSessionId).exchange().expectStatus().isOk();
        client.delete().uri("/api/interview-sessions/{id}", emptyId).exchange().expectStatus().isOk();
        client.delete().uri("/api/interview-sessions/{id}", 999999).exchange()
                .expectStatus().isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("出题持续失败：自动重试 3 次后 FAILED，错误可见")
    void interviewGenerationFailure() {
        createProvider("bad", upstreamBase + "/bad");
        createModel("bad", MODEL);
        setGlobal("bad");

        JsonNode created = postJson("/api/interview-sessions", Map.of("direction", "java-backend")).path("data");
        long sessionId = created.path("id").asLong();
        JsonNode detail = awaitSession(sessionId, "FAILED");
        assertThat(detail.path("retryCount").asInt()).isEqualTo(3);
        assertThat(detail.path("error").asText()).contains("HTTP 401");
        assertThat(detail.path("questions")).isEmpty();
    }

    @Test
    @DisplayName("Offer 全链路：自动带公司、保留组上限、固定对比矩阵、提取、分析")
    void offerPipeline() {
        configureDefaultProvider();
        long companyId = postJson("/api/companies", Map.of("name", "字节跳动")).path("data").path("id").asLong();
        long jobId = insertJob(companyId, "Java 后端开发工程师", "Java Spring Redis");

        // 仅填岗位 → 自动带出公司；默认进入保留组
        JsonNode first = postJson("/api/offers", Map.of("jobPositionId", jobId, "monthlySalary", 30000,
                "baseSalary", 24000, "performanceRatio", 20, "benefits", List.of("餐补"),
                "allowances", List.of("房补"), "annualBonus", "3 个月", "remark", "首选")).path("data");
        long firstId = first.path("id").asLong();
        assertThat(first.path("companyId").asLong()).isEqualTo(companyId);
        assertThat(first.path("companyName").asText()).isEqualTo("字节跳动");
        assertThat(first.path("jobPositionTitle").asText()).isEqualTo("Java 后端开发工程师");
        assertThat(first.path("group").asText()).isEqualTo("KEEP");
        assertThat(first.path("analysis").isNull()).isTrue();

        long secondId = createOffer(companyId, 28000);
        long thirdId = createOffer(companyId, 26000);
        long fourthId = createOffer(companyId, 25000);
        request("/api/offers", "POST", Map.of("companyId", companyId, "monthlySalary", 24000),
                HttpStatus.CONFLICT, 40914);
        long dropId = postJson("/api/offers", Map.of("companyId", companyId, "monthlySalary", 24000,
                "group", "DROP")).path("data").path("id").asLong();

        // 对比矩阵：4 列、12 行固定顺序，缺失值统一「未填写」
        JsonNode comparison = getJson("/api/offers/comparison").path("data");
        assertThat(comparison.path("columns")).hasSize(4);
        assertThat(comparison.path("rows")).hasSize(12);
        assertThat(comparison.path("rows").get(0).path("key").asText()).isEqualTo("monthlySalary");
        assertThat(comparison.path("rows").get(11).path("key").asText()).isEqualTo("remark");
        assertThat(comparison.path("rows").get(0).path("values")).hasSize(4);
        assertThat(comparison.path("rows").get(1).path("values").get(1).asText()).isEqualTo("未填写");
        assertThat(comparison.path("rows").get(4).path("values").get(0).asText()).isEqualTo("未填写");

        // 显式 null 清空 + 字段更新；移到淘汰组后保留组腾出位置
        JsonNode updated = request("/api/offers/" + firstId, "PUT",
                jsonOf("{\"remark\":null,\"monthlySalary\":31000,\"benefits\":[\"餐补\",\"班车\"],"
                        + "\"allowances\":null}"), HttpStatus.OK, null).path("data");
        assertThat(updated.path("remark").isNull()).isTrue();
        assertThat(updated.path("monthlySalary").asInt()).isEqualTo(31000);
        assertThat(updated.path("benefits")).hasSize(2);
        assertThat(updated.path("allowances")).isEmpty();
        client.put().uri("/api/offers/{id}", dropId).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("group", "KEEP")).exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody().jsonPath("$.code").isEqualTo(40914);
        request("/api/offers/" + firstId, "PUT", Map.of("group", "DROP"), HttpStatus.OK, null);
        client.put().uri("/api/offers/{id}", dropId).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("group", "KEEP")).exchange().expectStatus().isOk();
        assertThat(getJson("/api/offers?group=KEEP").path("data").path("total").asInt()).isEqualTo(4);
        assertThat(getJson("/api/offers?group=DROP").path("data").path("total").asInt()).isEqualTo(1);

        // 文本提取：不落库；图片提取走视觉消息
        int before = jdbc.queryForObject("SELECT count(*) FROM offers", Integer.class);
        JsonNode extracted = multipart("/api/offers/extract", "text", "字节 30K 双休 年终3个月")
                .path("data");
        assertThat(extracted.path("companyName").asText()).isEqualTo("字节跳动");
        assertThat(extracted.path("monthlySalary").asInt()).isEqualTo(30000);
        assertThat(extracted.path("benefits")).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM offers", Integer.class)).isEqualTo(before);
        JsonNode imageExtracted = multipartFile("/api/offers/extract", "image", "offer.png",
                "image/png", new byte[] {1, 2, 3});
        assertThat(imageExtracted.path("data").path("companyName").asText()).isEqualTo("字节跳动");
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("image", new ByteArrayResource(new byte[] {1}) {
            @Override
            public String getFilename() {
                return "offer.gif";
            }
        }).contentType(MediaType.IMAGE_GIF);
        client.post().uri("/api/offers/extract").contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build())).exchange()
                .expectStatus().isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .expectBody().jsonPath("$.code").isEqualTo(41501);

        // 前景分析：默认基于所给资料；联网开关影响来源标注；结果持久化到详情
        JsonNode analysis = postJson("/api/offers/" + firstId + "/analyze", Map.of()).path("data");
        assertThat(analysis.path("tendency").asText()).isEqualTo("ACCEPT");
        assertThat(analysis.path("basedOnProvidedMaterials").asBoolean()).isTrue();
        assertThat(analysis.path("sources")).isEmpty();
        assertThat(analysis.path("llmProvider").asText()).isEqualTo("stub");
        JsonNode detail = getJson("/api/offers/" + firstId).path("data");
        assertThat(detail.path("analysis").path("tendency").asText()).isEqualTo("ACCEPT");
        JsonNode web = postJson("/api/offers/" + firstId + "/analyze", Map.of("webSearch", true)).path("data");
        assertThat(web.path("basedOnProvidedMaterials").asBoolean()).isFalse();
        assertThat(web.path("sources").get(0).asText()).contains("官网");

        // 校验与删除
        request("/api/offers", "POST", Map.of("monthlySalary", 10000), HttpStatus.BAD_REQUEST, 40001);
        request("/api/offers", "POST", Map.of("companyId", 999999), HttpStatus.NOT_FOUND, 40401);
        request("/api/offers", "POST", Map.of("jobPositionId", 999999), HttpStatus.NOT_FOUND, 40401);
        request("/api/offers/" + firstId, "PUT", Map.of("monthlySalary", -1),
                HttpStatus.BAD_REQUEST, 40001);
        request("/api/offers/" + firstId + "/analyze", "POST", Map.of("webSearch", false),
                HttpStatus.OK, null);
        client.delete().uri("/api/offers/{id}", firstId).exchange().expectStatus().isOk();
        client.get().uri("/api/offers/{id}", firstId).exchange()
                .expectStatus().isEqualTo(HttpStatus.NOT_FOUND);
        client.delete().uri("/api/offers/{id}", 999999).exchange()
                .expectStatus().isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---------- 辅助 ----------

    private static String questions(int count) {
        StringBuilder builder = new StringBuilder("{\"questions\":[");
        for (int index = 1; index <= count; index++) {
            if (index > 1) {
                builder.append(',');
            }
            builder.append("{\"content\":\"Java 面试题 ").append(index)
                    .append("：请说明你的项目经验与取舍。\",\"topicSummary\":\"知识点 ")
                    .append(index).append("\",\"difficulty\":\"").append(index % 3 == 0 ? "EASY" : "MEDIUM")
                    .append("\",\"keyPoints\":[\"要点 ").append(index)
                    .append("\"],\"referenceAnswer\":\"参考答案 ").append(index).append("\"}");
        }
        return builder.append("]}").toString();
    }

    private static Map<String, Object> jsonOf(String json) {
        try {
            return MAPPER.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() { });
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private long insertJob(long companyId, String title, String rawText) {
        jdbc.update("INSERT INTO job_positions (user_id, company_id, title, status, parse_status, source_type, "
                + "raw_text, must_have_skills, nice_to_have_skills) "
                + "VALUES (1, ?, ?, 'SAVED', 'COMPLETED', 'TEXT', ?, '[]', '[]')",
                companyId, title, rawText);
        return jdbc.queryForObject("SELECT max(id) FROM job_positions", Long.class);
    }

    private long createOffer(long companyId, int monthlySalary) {
        return postJson("/api/offers", Map.of("companyId", companyId, "monthlySalary", monthlySalary))
                .path("data").path("id").asLong();
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

    private JsonNode getJson(String uri) {
        JsonNode body = client.get().uri(uri).exchange().expectStatus().isOk()
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

    private JsonNode patchJson(String uri, Map<String, Object> payload) {
        JsonNode body = client.patch().uri(uri).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload).exchange().expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
        assertThat(body).isNotNull();
        return body;
    }

    /** 兼容 GET/POST/PUT 的统一请求封装，并断言业务码。 */
    private JsonNode request(String uri, String method, Object payload, HttpStatus status, Integer code) {
        WebTestClient.RequestBodySpec spec = switch (method) {
            case "POST" -> client.post().uri(uri).contentType(MediaType.APPLICATION_JSON);
            case "PUT" -> client.put().uri(uri).contentType(MediaType.APPLICATION_JSON);
            case "PATCH" -> client.patch().uri(uri).contentType(MediaType.APPLICATION_JSON);
            default -> null;
        };
        WebTestClient.ResponseSpec response = spec == null
                ? client.get().uri(uri).exchange()
                : spec.bodyValue(payload == null ? Map.of() : payload).exchange();
        response.expectStatus().isEqualTo(status);
        JsonNode body = response.expectBody(JsonNode.class).returnResult().getResponseBody();
        assertThat(body).isNotNull();
        if (code != null) {
            assertThat(body.path("code").asInt()).isEqualTo(code);
        }
        return body;
    }

    private JsonNode multipart(String uri, String part, String value) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part(part, value);
        return client.post().uri(uri).contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build())).exchange().expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
    }

    private JsonNode multipartFile(String uri, String part, String filename, String contentType, byte[] bytes) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part(part, new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        }).contentType(MediaType.parseMediaType(contentType));
        return client.post().uri(uri).contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build())).exchange().expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
    }

    private JsonNode awaitSession(long sessionId, String expected) {
        return await("/api/interview-sessions/" + sessionId, "status", expected, "会话 " + sessionId);
    }

    private JsonNode awaitEvaluation(long sessionId, String expected) {
        return await("/api/interview-sessions/" + sessionId + "/evaluation", "status", expected,
                "评估 " + sessionId);
    }

    private JsonNode await(String uri, String field, String expected, String label) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        JsonNode data = null;
        while (System.nanoTime() < deadline) {
            JsonNode body = client.get().uri(uri).exchange().expectStatus().isOk()
                    .expectBody(JsonNode.class).returnResult().getResponseBody();
            assertThat(body).isNotNull();
            data = body.path("data");
            if (expected.equals(data.path(field).asText())) {
                return data;
            }
            try {
                Thread.sleep(100);
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
