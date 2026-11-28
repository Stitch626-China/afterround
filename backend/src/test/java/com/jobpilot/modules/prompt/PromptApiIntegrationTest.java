package com.jobpilot.modules.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobpilot.AbstractIntegrationTest;
import com.jobpilot.modules.prompt.catalog.PromptCatalog;
import com.jobpilot.modules.prompt.service.PromptRegistry;
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
 * 提示词工作台 8 端点集成测试：真实 PostgreSQL + Flyway。
 *
 * <p>覆盖内置兜底、草稿、发布、版本历史、回滚、无效草稿 400、未知 key 404、
 * 丢弃幂等与草稿不进入历史列表。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIf("com.jobpilot.AbstractIntegrationTest#dockerAvailable")
class PromptApiIntegrationTest extends AbstractIntegrationTest {

    private static final String KEY = "resume-analysis";
    private static final String BASE = "/api/llm/prompts";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PromptCatalog catalog;

    @Autowired
    private PromptRegistry registry;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port)
                .responseTimeout(Duration.ofSeconds(30)).build();
        jdbc.update("DELETE FROM llm_prompt_versions");
        catalog.all().forEach(spec -> registry.invalidate(spec.key()));
    }

    @Test
    @DisplayName("列表 12 组、详情回退内置默认、未知 key 404")
    void listsPromptsAndFallsBackToBuiltin() {
        client.get().uri(BASE).exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(12)
                .jsonPath("$.data[0].key").isEqualTo(KEY)
                .jsonPath("$.data[0].activeVersion.source").isEqualTo("BUILTIN")
                .jsonPath("$.data[0].activeVersion.versionNo").isEqualTo(0)
                .jsonPath("$.data[0].builtinVersion").isEqualTo("resume-analysis-v3")
                .jsonPath("$.data[0].hasEvalCorpus").isEqualTo(true)
                .jsonPath("$.data[0].placeholders[0]").isEqualTo("resumeText")
                .jsonPath("$.data[0].hasDraft").isEqualTo(false);

        client.get().uri(BASE + "/" + KEY).exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.active.source").isEqualTo("BUILTIN")
                .jsonPath("$.data.active.versionNo").isEqualTo(0)
                .jsonPath("$.data.builtIn.versionNo").isEqualTo(0)
                .jsonPath("$.data.builtIn.systemContent").isNotEmpty()
                .jsonPath("$.data.builtIn.userContent").isNotEmpty()
                .jsonPath("$.data.hasDraft").isEqualTo(false)
                .jsonPath("$.data.validation.valid").isEqualTo(true);

        client.get().uri(BASE + "/not-exist").exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo(40401);
        client.put().uri(BASE + "/not-exist/draft").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("systemContent", "s", "userContent", "u"))
                .exchange().expectStatus().isNotFound();
    }

    @Test
    @DisplayName("发布说明超过 200 字返回 400，不会误报版本冲突")
    void publishRejectsTooLongNote() {
        JsonNode builtIn = builtIn();
        saveDraft(builtIn.path("systemContent").asText(), builtIn.path("userContent").asText(), "首版")
                .expectStatus().isOk();
        client.post().uri(BASE + "/" + KEY + "/publish").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("note", "x".repeat(201)))
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("草稿→发布→第二版→回滚，版本历史只含 ACTIVE/ARCHIVED")
    void draftPublishAndRollbackFlow() {
        JsonNode builtIn = builtIn();
        String system = builtIn.path("systemContent").asText();
        String user = builtIn.path("userContent").asText();

        saveDraft(system, user, "首版").expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.hasDraft").isEqualTo(true)
                .jsonPath("$.data.draft.versionNo").isEqualTo(1)
                .jsonPath("$.data.draft.status").isEqualTo("DRAFT")
                .jsonPath("$.data.active.source").isEqualTo("BUILTIN")
                .jsonPath("$.data.validation.valid").isEqualTo(true)
                .jsonPath("$.data.validation.warnings[0]").isEqualTo("存在未发布草稿；发布后才会生效");

        // 草稿不进入版本历史
        client.get().uri(BASE + "/" + KEY + "/versions").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.total").isEqualTo(0);

        publish("发布 v1").expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.hasDraft").isEqualTo(false)
                .jsonPath("$.data.activeVersion.source").isEqualTo("DB")
                .jsonPath("$.data.activeVersion.versionNo").isEqualTo(1)
                .jsonPath("$.data.activeVersion.status").isEqualTo("ACTIVE")
                .jsonPath("$.data.active.versionNo").isEqualTo(1);

        client.get().uri(BASE + "/" + KEY + "/versions").exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.total").isEqualTo(1)
                .jsonPath("$.data.items[0].versionNo").isEqualTo(1)
                .jsonPath("$.data.items[0].status").isEqualTo("ACTIVE");

        String secondSystem = system + "\n\n补充：更严格。";
        saveDraft(secondSystem, user, "第二版").expectStatus().isOk()
                .expectBody().jsonPath("$.data.draft.versionNo").isEqualTo(2);
        publish("发布 v2").expectStatus().isOk()
                .expectBody().jsonPath("$.data.activeVersion.versionNo").isEqualTo(2);

        client.get().uri(BASE + "/" + KEY + "/versions").exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.total").isEqualTo(2)
                .jsonPath("$.data.items[0].versionNo").isEqualTo(2)
                .jsonPath("$.data.items[0].status").isEqualTo("ACTIVE")
                .jsonPath("$.data.items[1].versionNo").isEqualTo(1)
                .jsonPath("$.data.items[1].status").isEqualTo("ARCHIVED");

        client.get().uri(BASE + "/" + KEY + "/versions/2").exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.status").isEqualTo("ACTIVE")
                .jsonPath("$.data.systemContent").value(text -> assertThat(text.toString()).contains("更严格"));

        client.post().uri(BASE + "/" + KEY + "/versions/1/activate").exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.activeVersion.versionNo").isEqualTo(1)
                .jsonPath("$.data.active.status").isEqualTo("ACTIVE")
                .jsonPath("$.data.hasDraft").isEqualTo(false);

        client.get().uri(BASE + "/" + KEY + "/versions/2").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.status").isEqualTo("ARCHIVED");

        client.get().uri(BASE + "/" + KEY + "/versions").exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.items[0].versionNo").isEqualTo(2)
                .jsonPath("$.data.items[0].status").isEqualTo("ARCHIVED")
                .jsonPath("$.data.items[1].versionNo").isEqualTo(1)
                .jsonPath("$.data.items[1].status").isEqualTo("ACTIVE");

        client.get().uri(BASE + "/" + KEY + "/versions/1").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.systemContent").isEqualTo(system);

        // 激活当前已生效版本幂等
        client.post().uri(BASE + "/" + KEY + "/versions/1/activate").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.activeVersion.versionNo").isEqualTo(1);
    }

    @Test
    @DisplayName("无效草稿可保存不可发布；丢弃幂等且更新不追加版本号")
    void invalidDraftCannotBePublishedAndDiscardIsIdempotent() {
        saveDraft("system 缺少占位符", "user 也缺少占位符", "无效").expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.validation.valid").isEqualTo(false)
                .jsonPath("$.data.draft.versionNo").isEqualTo(1);

        saveDraft("第二次仍然无效", "还是没有占位符", "无效改稿").expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.draft.versionNo").isEqualTo(1)
                .jsonPath("$.data.draft.note").isEqualTo("无效改稿");

        publish("试图发布").expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo(40001);

        client.delete().uri(BASE + "/" + KEY + "/draft").exchange().expectStatus().isOk();
        client.get().uri(BASE + "/" + KEY).exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.hasDraft").isEqualTo(false)
                .jsonPath("$.data.active.source").isEqualTo("BUILTIN");

        // 幂等：没有草稿时再次丢弃仍 200
        client.delete().uri(BASE + "/" + KEY + "/draft").exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("草稿不能直接激活；不存在的版本 404")
    void draftCannotBeActivatedAndMissingVersionIsNotFound() {
        JsonNode builtIn = builtIn();
        saveDraft(builtIn.path("systemContent").asText(), builtIn.path("userContent").asText(), "草稿")
                .expectStatus().isOk();

        client.post().uri(BASE + "/" + KEY + "/versions/1/activate").exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo(40001);
        client.post().uri(BASE + "/" + KEY + "/versions/99/activate").exchange().expectStatus().isNotFound();
        client.get().uri(BASE + "/" + KEY + "/versions/99").exchange().expectStatus().isNotFound();
        client.get().uri(BASE + "/not-exist/versions").exchange().expectStatus().isNotFound();
    }

    private JsonNode builtIn() {
        JsonNode body = client.get().uri(BASE + "/" + KEY).exchange().expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
        assertThat(body).isNotNull();
        return body.path("data").path("builtIn");
    }

    private WebTestClient.ResponseSpec saveDraft(String system, String user, String note) {
        return client.put().uri(BASE + "/" + KEY + "/draft").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("systemContent", system, "userContent", user, "note", note))
                .exchange();
    }

    private WebTestClient.ResponseSpec publish(String note) {
        return client.post().uri(BASE + "/" + KEY + "/publish").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("note", note)).exchange();
    }
}
