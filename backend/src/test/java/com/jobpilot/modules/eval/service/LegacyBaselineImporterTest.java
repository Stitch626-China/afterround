package com.jobpilot.modules.eval.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.jobpilot.modules.eval.core.EvalSceneCatalog;
import com.jobpilot.modules.eval.entity.LlmEvalBaseline;
import com.jobpilot.modules.eval.entity.LlmEvalRun;
import com.jobpilot.modules.eval.entity.LlmEvalRunCase;
import com.jobpilot.modules.eval.mapper.LlmEvalBaselineMapper;
import com.jobpilot.modules.eval.mapper.LlmEvalRunCaseMapper;
import com.jobpilot.modules.eval.mapper.LlmEvalRunMapper;
import com.jobpilot.modules.prompt.service.PromptRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 旧 JSON 基线导入：3 个场景各 1 条 current，promptHash 只在版本不一致时标 legacy，重复执行不重复导入。 */
@ExtendWith(MockitoExtension.class)
class LegacyBaselineImporterTest {

    @Mock
    private LlmEvalBaselineMapper baselineMapper;
    @Mock
    private LlmEvalRunMapper runMapper;
    @Mock
    private LlmEvalRunCaseMapper caseMapper;

    private final EvalSceneCatalog catalog = new EvalSceneCatalog();
    private final PromptRegistry registry = PromptRegistry.builtinOnly();
    private final List<LlmEvalRunCase> insertedCases = new ArrayList<>();
    private final List<LlmEvalBaseline> insertedBaselines = new ArrayList<>();
    private long nextRunId = 100;

    @BeforeAll
    static void initLambdaCache() {
        com.jobpilot.LambdaCacheSupport.init(LlmEvalRun.class, LlmEvalRunCase.class, LlmEvalBaseline.class);
    }

    private LegacyBaselineImporter importer() {
        return new LegacyBaselineImporter(baselineMapper, runMapper, caseMapper, catalog, registry);
    }

    @Test
    void importsOnceAndKeepsLegacyHashOnlyWhenVersionChanged() throws Exception {
        when(baselineMapper.selectCount(any())).thenReturn(0L);
        when(runMapper.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            invocation.getArgument(0, LlmEvalRun.class).setId(++nextRunId);
            return 1;
        }).when(runMapper).insert(any(LlmEvalRun.class));
        doAnswer(invocation -> {
            insertedCases.add(invocation.getArgument(0));
            return 1;
        }).when(caseMapper).insert(any(LlmEvalRunCase.class));
        when(caseMapper.selectList(any())).thenAnswer(invocation -> insertedCases);
        doAnswer(invocation -> {
            insertedBaselines.add(invocation.getArgument(0));
            return 1;
        }).when(baselineMapper).insert(any(LlmEvalBaseline.class));

        importer().run(null);

        assertThat(insertedBaselines).hasSize(3);
        assertThat(insertedCases).hasSize(27);
        Map<String, LlmEvalBaseline> byScene = insertedBaselines.stream()
                .collect(Collectors.toMap(LlmEvalBaseline::getScene, Function.identity()));
        assertThat(byScene.keySet()).containsExactlyInAnyOrder("resume-analysis", "jd-parse", "review-analyze");
        assertThat(byScene.values()).allMatch(row -> "legacy".equals(row.getProviderKey()));
        assertThat(byScene.get("resume-analysis").getPromptVersion()).isEqualTo("resume-analysis-v2");
        assertThat(byScene.get("resume-analysis").getPromptHash()).isEqualTo("legacy");
        assertThat(byScene.get("jd-parse").getPromptHash()).isNotEqualTo("legacy");
        assertThat(byScene.get("review-analyze").getPromptHash()).isNotEqualTo("legacy");

        when(baselineMapper.selectCount(any())).thenReturn(3L);
        importer().run(null);
        assertThat(insertedBaselines).hasSize(3);
    }
}
