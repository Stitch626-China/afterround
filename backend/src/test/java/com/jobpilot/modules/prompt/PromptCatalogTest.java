package com.jobpilot.modules.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.jobpilot.modules.prompt.catalog.PromptCatalog;
import com.jobpilot.modules.prompt.catalog.PromptOutputMode;
import com.jobpilot.modules.prompt.catalog.PromptSpec;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** 12 组提示词元数据必须与实际模板保持一致。 */
class PromptCatalogTest {

    private final PromptCatalog catalog = new PromptCatalog();

    @Test
    void catalogCoversAllTwelvePrompts() {
        List<PromptSpec> all = catalog.all();
        assertThat(all).hasSize(12);
        assertThat(all).extracting(PromptSpec::key).doesNotHaveDuplicates();
        assertThat(all).extracting(PromptSpec::key).containsExactlyInAnyOrder(
                "resume-analysis", "resume-comparison", "job-position-parse", "job-position-ocr",
                "job-match", "review-analyze", "insights-summary", "interview-question",
                "interview-followup", "interview-evaluate", "offer-extract", "offer-analysis");
    }

    @Test
    void everyPromptFileExistsOnClasspath() {
        for (PromptSpec spec : catalog.all()) {
            assertThat(new ClassPathResource(spec.systemFile()).exists())
                    .as(spec.key() + " system").isTrue();
            assertThat(new ClassPathResource(spec.userFile()).exists())
                    .as(spec.key() + " user").isTrue();
        }
    }

    @Test
    void everyEvalPromptHasScene() {
        assertThat(catalog.all().stream()
                .filter(spec -> spec.evalScene() != null)
                .map(PromptSpec::key))
                .containsExactlyInAnyOrder("resume-analysis", "job-position-parse", "review-analyze");
    }

    @Test
    void placeholdersExistInTemplates() throws IOException {
        for (PromptSpec spec : catalog.all()) {
            String template = read(spec.systemFile()) + read(spec.userFile());
            for (String placeholder : spec.placeholders()) {
                assertThat(template)
                        .as(spec.key() + " 缺少占位符 {{" + placeholder + "}}")
                        .contains("{{" + placeholder + "}}");
            }
        }
    }

    @Test
    void requiredFieldsExistInSystemTemplate() throws IOException {
        for (PromptSpec spec : catalog.all()) {
            if (spec.outputMode() != PromptOutputMode.JSON) {
                continue;
            }
            String system = read(spec.systemFile());
            for (String field : spec.requiredFields()) {
                assertThat(system)
                        .as(spec.key() + " system 缺少字段 " + field)
                        .contains(field);
            }
        }
    }

    private static String read(String path) throws IOException {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
