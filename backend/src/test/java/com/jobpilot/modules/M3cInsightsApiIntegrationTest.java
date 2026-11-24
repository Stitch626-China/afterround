package com.jobpilot.modules;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import com.jobpilot.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * M3-c 跨场洞察集成测试：真实 PostgreSQL 验证 JSONB 展开、公司 / 轮次 / 问题聚合、
 * 投递漏斗、改进验证率与 N<5 样本保护（不依赖 LLM）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIf("com.jobpilot.AbstractIntegrationTest#dockerAvailable")
class M3cInsightsApiIntegrationTest extends AbstractIntegrationTest {

    private static final String ANALYSIS_JSON = """
            {
              "questions": [
                {"questionIndex": 1, "question": "Explain JVM GC",
                 "topics": ["jvm"], "lossTags": ["shallow-depth"]}
              ],
              "topics": [{"tag": "jvm", "label": "JVM 与 GC"}],
              "lossTags": [{"tag": "shallow-depth", "label": "深度不足"}],
              "summary": {"overall": "需要补深度"},
              "actions": [],
              "promptVersion": "review-analyze-v1",
              "rubricVersion": "interview-rubric-v1",
              "taxonomyVersion": "taxonomy-v1",
              "confirmed": true,
              "degraded": false
            }
            """;

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(30)).build();
        cleanup();
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("已确认复盘跨场聚合并输出趋势与改进验证率")
    void aggregatesConfirmedReviews() {
        Seed seed = seed(6, 3);
        jdbc.update("INSERT INTO improvement_actions (action, status, source_review_id) VALUES (?, 'VERIFIED', ?)",
                "补 JVM 笔记", seed.firstReviewId());
        jdbc.update("INSERT INTO improvement_actions (action, status, source_review_id) VALUES (?, 'TODO', ?)",
                "练习表达", seed.firstReviewId());

        client.get().uri("/api/insights?days=365").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.sample.confirmedReviews").isEqualTo(6)
                .jsonPath("$.data.sample.minimum").isEqualTo(5)
                .jsonPath("$.data.sample.sufficient").isEqualTo(true)
                .jsonPath("$.data.topics[0].topic").isEqualTo("jvm")
                .jsonPath("$.data.topics[0].label").isEqualTo("JVM 与 GC")
                .jsonPath("$.data.topics[0].interviewCount").isEqualTo(6)
                .jsonPath("$.data.topics[0].failCount").isEqualTo(3)
                .jsonPath("$.data.topics[0].failRate").isEqualTo(0.5)
                .jsonPath("$.data.lossTags[0].count").isEqualTo(6)
                .jsonPath("$.data.roundPassRates[0].passRate").isEqualTo(0.5)
                .jsonPath("$.data.companyProfiles[0].companyName").isEqualTo(seed.companyName())
                .jsonPath("$.data.companyProfiles[0].interviewCount").isEqualTo(6)
                .jsonPath("$.data.companyProfiles[0].topTopics[0].tag").isEqualTo("jvm")
                .jsonPath("$.data.topQuestions[0].question").isEqualTo("Explain JVM GC")
                .jsonPath("$.data.topQuestions[0].askedCount").isEqualTo(6)
                .jsonPath("$.data.funnel.applications").isEqualTo(1)
                .jsonPath("$.data.funnel.interviews").isEqualTo(1)
                .jsonPath("$.data.improvements.verified").isEqualTo(1)
                .jsonPath("$.data.improvements.todo").isEqualTo(1)
                .jsonPath("$.data.improvements.verificationRate").isEqualTo(0.5)
                .jsonPath("$.data.summary.content").value(content ->
                        assertThat(content.toString()).contains("深度不足").contains("通过率最低"));
    }

    @Test
    @DisplayName("N<5 时只返回原始统计并标记样本不足，公司筛选生效")
    void guardsSmallSampleAndFiltersByCompany() {
        Seed seed = seed(1, 1);

        client.get().uri(builder -> builder.path("/api/insights")
                        .queryParam("days", 365)
                        .queryParam("companyId", seed.companyId()).build())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.sample.confirmedReviews").isEqualTo(1)
                .jsonPath("$.data.sample.sufficient").isEqualTo(false)
                .jsonPath("$.data.topics.length()").isEqualTo(1)
                .jsonPath("$.data.companyProfiles.length()").isEqualTo(1)
                .jsonPath("$.data.summary.content").value(content ->
                        assertThat(content.toString()).contains("样本不足"));
    }

    private Seed seed(int reviews, int passCount) {
        String companyName = "InsightIT-" + System.nanoTime();
        long companyId = insert("INSERT INTO companies (name, tags) VALUES (?, '[]'::jsonb) RETURNING id",
                companyName);
        long jobId = insert("""
                INSERT INTO job_positions (company_id, title, source_type, raw_text, parse_status)
                VALUES (?, 'Java Backend Engineer', 'TEXT', 'JD', 'COMPLETED') RETURNING id
                """, companyId);
        long applicationId = insert(
                "INSERT INTO applications (job_position_id, status) VALUES (?, 'INTERVIEWING') RETURNING id", jobId);

        long firstReviewId = 0;
        for (int index = 1; index <= reviews; index++) {
            String result = index <= passCount ? "PASS" : "FAIL";
            long eventId = insert("""
                    INSERT INTO interview_events (application_id, round_no, round_type, result, interview_time)
                    VALUES (?, ?, 'TECH', ?, now()) RETURNING id
                    """, applicationId, index, result);
            long reviewId = insert("""
                    INSERT INTO review_sessions (event_id, input_type, status, analyze_status, confirmed)
                    VALUES (?, 'TEXT', 'READY', 'COMPLETED', TRUE) RETURNING id
                    """, eventId);
            jdbc.update("""
                    INSERT INTO review_analyses (review_session_id, analysis, prompt_version, rubric_version,
                                                 taxonomy_version)
                    VALUES (?, ?::jsonb, 'review-analyze-v1', 'interview-rubric-v1', 'taxonomy-v1')
                    """, reviewId, ANALYSIS_JSON);
            if (firstReviewId == 0) {
                firstReviewId = reviewId;
            }
        }
        return new Seed(companyName, companyId, firstReviewId);
    }

    private long insert(String sql, Object... args) {
        Long id = jdbc.queryForObject(sql, Long.class, args);
        return id == null ? 0 : id;
    }

    private void cleanup() {
        jdbc.update("DELETE FROM improvement_actions");
        jdbc.update("DELETE FROM review_analyses");
        jdbc.update("DELETE FROM review_sessions");
        jdbc.update("DELETE FROM interview_events");
        jdbc.update("DELETE FROM applications");
        jdbc.update("DELETE FROM job_positions WHERE company_id IN (SELECT id FROM companies WHERE name LIKE 'InsightIT-%')");
        jdbc.update("DELETE FROM companies WHERE name LIKE 'InsightIT-%'");
    }

    private record Seed(String companyName, long companyId, long firstReviewId) {
    }
}
