package com.jobpilot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 启动与迁移集成测试：用 Testcontainers 起真实 PostgreSQL，验证
 * <ol>
 *   <li>Spring 上下文可正常启动；</li>
 *   <li>Flyway 迁移 V1 成功执行，M1 需要的表全部存在。</li>
 * </ol>
 *
 * <p><b>需要本机 Docker 守护进程运行</b>；未运行时整类自动跳过（不判定失败）。
 */
@SpringBootTest
class JobPilotApplicationTests extends AbstractIntegrationTest {

    private static final List<String> EXPECTED_TABLES = List.of(
            "llm_providers", "llm_models", "llm_global_setting", "llm_call_logs",
            "chat_sessions", "chat_messages",
            "resumes", "resume_analyses");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Spring 上下文加载成功")
    void contextLoads() {
        assertThat(jdbcTemplate).isNotNull();
    }

    @Test
    @DisplayName("Flyway 迁移后 M1 全部业务表存在")
    void migrationCreatesAllM1Tables() {
        List<String> actual = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(actual).containsAll(EXPECTED_TABLES);
        assertThat(actual).contains("flyway_schema_history");
    }

    @Test
    @DisplayName("单行约束生效：llm_global_setting 只允许 id = 1")
    void globalSettingIsSingleRow() {
        Integer inserted = jdbcTemplate.update(
                "INSERT INTO llm_global_setting (default_provider_key, default_model) VALUES ('corp', 'm1')");
        assertThat(inserted).isEqualTo(1);

        assertThatThrownByIdConflict();
    }

    private void assertThatThrownByIdConflict() {
        try {
            jdbcTemplate.update("INSERT INTO llm_global_setting (id) VALUES (2)");
        } catch (Exception ex) {
            assertThat(ex.getMessage()).containsIgnoringCase("ck_llm_global_setting_single_row");
            return;
        }
        throw new AssertionError("应当违反 CHECK 约束 ck_llm_global_setting_single_row");
    }
}
