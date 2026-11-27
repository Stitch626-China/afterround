package com.jobpilot.modules.eval.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;

import com.jobpilot.modules.eval.core.EvalEngine;
import com.jobpilot.modules.eval.core.EvalSceneCatalog;
import com.jobpilot.modules.eval.core.EvalSceneCatalog.EvalScene;
import com.jobpilot.modules.eval.entity.LlmEvalBaseline;
import com.jobpilot.modules.eval.mapper.LlmEvalBaselineMapper;
import com.jobpilot.modules.llm.dto.LlmScene;
import com.jobpilot.modules.llm.service.LlmSceneService;
import com.jobpilot.modules.llm.service.LlmSceneService.SceneModel;
import com.jobpilot.modules.prompt.catalog.PromptCatalog;
import com.jobpilot.modules.prompt.catalog.PromptVersionSource;
import com.jobpilot.modules.prompt.service.PromptRegistry;
import com.jobpilot.modules.prompt.service.PromptTemplate;
import com.jobpilot.modules.resume.ai.AiResumeAnalysis;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 影响面：提示词 / 模型 / 语料 / 引擎版本变化时只建议对应场景；无基线给出「尚无基线」。 */
@ExtendWith(MockitoExtension.class)
class EvalImpactAnalyzerTest {

    @Mock
    private EvalSceneCatalog catalog;
    @Mock
    private PromptRegistry registry;
    @Mock
    private LlmSceneService sceneService;
    @Mock
    private LlmEvalBaselineMapper baselineMapper;

    @BeforeAll
    static void initLambdaCache() {
        com.jobpilot.LambdaCacheSupport.init(LlmEvalBaseline.class);
    }

    private EvalImpactAnalyzer analyzer() {
        return new EvalImpactAnalyzer(catalog, new PromptCatalog(), registry, sceneService, baselineMapper);
    }

    private static EvalScene resumeScene() {
        return new EvalScene("resume-analysis", "简历分析", "resume-analysis",
                LlmScene.RESUME_ANALYZE, false, AiResumeAnalysis.class);
    }

    private void stubScene() {
        when(catalog.get("resume-analysis")).thenReturn(resumeScene());
    }

    private void stubSceneConfig() {
        when(catalog.corpusHash("resume-analysis")).thenReturn("corpus");
        when(sceneService.resolve(LlmScene.RESUME_ANALYZE)).thenReturn(new SceneModel("p", "m"));
    }

    private void stubCaseCount() {
        when(catalog.caseCount("resume-analysis")).thenReturn(10);
    }

    private static LlmEvalBaseline baseline(String promptHash, String model, String engine, String corpus) {
        LlmEvalBaseline baseline = new LlmEvalBaseline();
        baseline.setId(3L);
        baseline.setScene("resume-analysis");
        baseline.setProviderKey("p");
        baseline.setModel(model);
        baseline.setPromptHash(promptHash);
        baseline.setCorpusHash(corpus);
        baseline.setEngineVersion(engine);
        baseline.setParams("{\"maxTokens\":8192,\"timeoutSeconds\":300,\"temperature\":0.2}");
        baseline.setCurrent(true);
        return baseline;
    }

    @Test
    void changedPromptSuggestsResumeScene() {
        stubScene();
        stubSceneConfig();
        stubCaseCount();
        when(registry.template("resume-analysis")).thenReturn(new PromptTemplate("resume-analysis",
                "s", "u", "v4", "new-hash", PromptVersionSource.BUILTIN));
        when(baselineMapper.selectOne(any())).thenReturn(baseline("old-hash", "m", EvalEngine.VERSION, "corpus"));

        var suggestions = analyzer().suggestForPrompt("resume-analysis");

        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.get(0).scene()).isEqualTo("resume-analysis");
        assertThat(suggestions.get(0).reasons()).containsExactly("提示词已更新");
        assertThat(suggestions.get(0).currentBaselineId()).isEqualTo(3L);
    }

    @Test
    void matchingConfigHasNoSuggestion() {
        stubScene();
        stubSceneConfig();
        stubCaseCount();
        when(registry.template("resume-analysis")).thenReturn(new PromptTemplate("resume-analysis",
                "s", "u", "v3", "same", PromptVersionSource.BUILTIN));
        when(baselineMapper.selectOne(any())).thenReturn(baseline("same", "m", EvalEngine.VERSION, "corpus"));

        assertThat(analyzer().suggestForPrompt("resume-analysis")).isEmpty();
    }

    @Test
    void modelAndEngineChangeSuggestRebuild() {
        stubScene();
        stubSceneConfig();
        when(registry.template("resume-analysis")).thenReturn(new PromptTemplate("resume-analysis",
                "s", "u", "v3", "same", PromptVersionSource.BUILTIN));
        when(baselineMapper.selectOne(any())).thenReturn(baseline("same", "old-model", "v0", "corpus"));

        assertThat(analyzer().rebuildReasons("resume-analysis"))
                .containsExactlyInAnyOrder("模型已更换", "断言引擎已升级");
    }

    @Test
    void noBaselineSuggestsFirstRun() {
        stubScene();
        stubCaseCount();
        when(baselineMapper.selectOne(any())).thenReturn(null);

        assertThat(analyzer().suggestForPrompt("resume-analysis").get(0).reasons())
                .containsExactly("尚无基线");
    }
}
