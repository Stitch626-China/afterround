package com.jobpilot.modules.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.jobpilot.modules.insights.prompt.InsightsPromptFactory;
import com.jobpilot.modules.interview.prompt.InterviewPromptFactory;
import com.jobpilot.modules.interview.service.InterviewDirectionService.Category;
import com.jobpilot.modules.interview.service.InterviewDirectionService.Direction;
import com.jobpilot.modules.job.prompt.JobMatchPromptFactory;
import com.jobpilot.modules.job.prompt.JobPositionPromptFactory;
import com.jobpilot.modules.offer.prompt.OfferPromptFactory;
import com.jobpilot.modules.prompt.catalog.PromptVersionSource;
import com.jobpilot.modules.prompt.service.PromptRegistry;
import com.jobpilot.modules.prompt.service.PromptTemplate;
import com.jobpilot.modules.resume.prompt.ResumeComparisonPromptFactory;
import com.jobpilot.modules.resume.prompt.ResumePromptFactory;
import com.jobpilot.modules.review.prompt.ReviewPromptFactory;
import org.junit.jupiter.api.Test;

/** 工厂不再自读 classpath：必须走注册表当前生效模板，并上报动态版本标签。 */
class PromptFactoryTest {

    private final PromptRegistry registry = mock(PromptRegistry.class);

    @Test
    void resumeFactoryUsesActiveTemplateAndDbVersion() {
        stub("resume-analysis", "R={{resumeText}} M={{marketDirections}}");
        ResumePromptFactory factory = new ResumePromptFactory(registry);

        assertThat(factory.system()).isEqualTo("S-resume-analysis");
        assertThat(factory.user("简历原文", "Java 后端")).isEqualTo("R=简历原文 M=Java 后端");
        assertThat(factory.user("简历原文", null)).contains("按简历本身判断");
        assertThat(factory.version()).isEqualTo("resume-analysis-db7");
    }

    @Test
    void comparisonFactoryUsesActiveTemplateAndDbVersion() {
        stub("resume-comparison", "{{base}}|{{target}}|{{diff}}");
        ResumeComparisonPromptFactory factory = new ResumeComparisonPromptFactory(registry);

        assertThat(factory.system()).isEqualTo("S-resume-comparison");
        assertThat(factory.user("A", "B", "C")).isEqualTo("A|B|C");
        assertThat(factory.version()).isEqualTo("resume-comparison-db7");
    }

    @Test
    void jobPositionFactorySeparatesParseAndOcrVersions() {
        stub("job-position-parse", "JD={{jdText}}");
        stub("job-position-ocr", "OCR 原文");
        JobPositionPromptFactory factory = new JobPositionPromptFactory(registry);

        assertThat(factory.system()).isEqualTo("S-job-position-parse");
        assertThat(factory.user("JD 文本")).isEqualTo("JD=JD 文本");
        assertThat(factory.ocrSystem()).isEqualTo("S-job-position-ocr");
        assertThat(factory.ocrUser()).isEqualTo("OCR 原文");
        assertThat(factory.parseVersion()).isEqualTo("job-position-parse-db7");
        assertThat(factory.ocrVersion()).isEqualTo("job-position-ocr-db7");
    }

    @Test
    void jobMatchFactoryUsesActiveTemplateAndDbVersion() {
        stub("job-match", "{{resumeText}}#{{jdText}}");
        JobMatchPromptFactory factory = new JobMatchPromptFactory(registry);

        assertThat(factory.system()).isEqualTo("S-job-match");
        assertThat(factory.user("简历", "JD")).isEqualTo("简历#JD");
        assertThat(factory.version()).isEqualTo("job-match-db7");
    }

    @Test
    void reviewFactoryUsesActiveTemplateAndDbVersion() {
        stub("review-analyze", "{{companyName}}|{{text}}");
        ReviewPromptFactory factory = new ReviewPromptFactory(registry);

        assertThat(factory.system()).isEqualTo("S-review-analyze");
        assertThat(factory.user(null, null, null, null, null, null, null, "转写"))
                .isEqualTo("未填写|转写");
        assertThat(factory.version()).isEqualTo("review-analyze-db7");
    }

    @Test
    void insightsFactoryUsesActiveTemplateAndDbVersion() {
        stub("insights-summary", "{{days}} 天：{{data}}");
        InsightsPromptFactory factory = new InsightsPromptFactory(registry);

        assertThat(factory.system()).isEqualTo("S-insights-summary");
        assertThat(factory.user(30, "统计")).isEqualTo("30 天：统计");
        assertThat(factory.version()).isEqualTo("insights-summary-db7");
    }

    @Test
    void interviewFactorySeparatesThreeVersions() {
        stub("interview-question", "P={{persona}} C={{categories}}",
                "R={{resumeSection}} H={{historyTopics}}");
        stub("interview-followup", "P={{persona}}", "A={{answer}}");
        stub("interview-evaluate", "P={{persona}} C={{categories}}", "T={{transcript}}");
        InterviewPromptFactory factory = new InterviewPromptFactory(registry);
        Direction direction = new Direction("java", "Java 后端", "desc", "严格面试官",
                List.of(new Category("basic", "基础", 50)), List.of("jvm"), 1);

        assertThat(factory.questionSystem(direction, 5, 2, true)).contains("严格面试官").contains("basic 基础 50%");
        assertThat(factory.questionUser(null, null, List.of("JVM"))).contains("只出方向题").contains("JVM");
        assertThat(factory.followUpSystem(direction, 2)).contains("严格面试官");
        assertThat(factory.followUpUser("Java", "主问题", "回答")).isEqualTo("A=回答");
        assertThat(factory.evaluateSystem(direction)).contains("basic 基础 50%");
        assertThat(factory.evaluateUser("Java", "整场转写")).isEqualTo("T=整场转写");
        assertThat(factory.questionVersion()).isEqualTo("interview-question-db7");
        assertThat(factory.followUpVersion()).isEqualTo("interview-followup-db7");
        assertThat(factory.evaluateVersion()).isEqualTo("interview-evaluate-db7");
    }

    @Test
    void offerFactorySeparatesExtractAndAnalysisVersions() {
        stub("offer-extract", "{{text}}");
        stub("offer-analysis", "{{searchRule}}", "{{materials}}");
        OfferPromptFactory factory = new OfferPromptFactory(registry);

        assertThat(factory.extractSystem()).isEqualTo("S-offer-extract");
        assertThat(factory.extractUser("Offer 原文")).isEqualTo("Offer 原文");
        assertThat(factory.analysisSystem(true)).contains("已开启联网");
        assertThat(factory.analysisSystem(false)).contains("未开启联网");
        assertThat(factory.analysisUser("资料")).contains("资料");
        assertThat(factory.extractVersion()).isEqualTo("offer-extract-db7");
        assertThat(factory.analysisVersion()).isEqualTo("offer-analysis-db7");
    }

    private void stub(String key, String userTemplate) {
        stub(key, "S-" + key, userTemplate);
    }

    private void stub(String key, String systemTemplate, String userTemplate) {
        when(registry.template(key)).thenReturn(new PromptTemplate(key, systemTemplate, userTemplate,
                key + "-db7", "hash", PromptVersionSource.DB));
        when(registry.version(key)).thenReturn(key + "-db7");
    }
}
