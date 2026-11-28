package com.jobpilot.modules.eval.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.modules.eval.core.EvalSceneCatalog;
import com.jobpilot.modules.eval.dto.CreateEvalBaselineRequest;
import com.jobpilot.modules.eval.dto.EvalBaselineResult;
import com.jobpilot.modules.eval.entity.LlmEvalBaseline;
import com.jobpilot.modules.eval.entity.LlmEvalRun;
import com.jobpilot.modules.eval.entity.LlmEvalRunCase;
import com.jobpilot.modules.eval.mapper.LlmEvalBaselineMapper;
import com.jobpilot.modules.eval.mapper.LlmEvalRunCaseMapper;
import com.jobpilot.modules.eval.mapper.LlmEvalRunMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 设为基线不重跑、逐场景部分成功、只保留一个当前基线、只切换标记。 */
@ExtendWith(MockitoExtension.class)
class EvalBaselineServiceTest {

    @Mock
    private LlmEvalBaselineMapper baselineMapper;
    @Mock
    private LlmEvalRunMapper runMapper;
    @Mock
    private LlmEvalRunCaseMapper caseMapper;
    @Mock
    private EvalSceneCatalog catalog;

    @BeforeAll
    static void initLambdaCache() {
        com.jobpilot.LambdaCacheSupport.init(LlmEvalRun.class, LlmEvalRunCase.class, LlmEvalBaseline.class);
    }

    private EvalBaselineService service() {
        return new EvalBaselineService(baselineMapper, runMapper, caseMapper, catalog);
    }

    private static LlmEvalRun run(boolean partial) {
        LlmEvalRun run = new LlmEvalRun();
        run.setId(1L);
        run.setStatus("COMPLETED");
        run.setPartial(partial);
        run.setScenes("[\"resume-analysis\"]");
        run.setModelSnapshot("[{\"scene\":\"resume-analysis\",\"providerKey\":\"p\",\"model\":\"m\"}]");
        run.setPromptSnapshot("[{\"scene\":\"resume-analysis\",\"promptKey\":\"resume-analysis\","
                + "\"promptVersion\":\"resume-analysis-v3\",\"promptHash\":\"hash\","
                + "\"system\":\"\",\"user\":\"\"}]");
        run.setParams("{\"repeat\":1}");
        run.setEngineVersion("llm-eval-v1");
        run.setCorpusHash("corpus");
        return run;
    }

    private static LlmEvalRunCase caseRow(String status) {
        LlmEvalRunCase row = new LlmEvalRunCase();
        row.setId(10L);
        row.setRunId(1L);
        row.setScene("resume-analysis");
        row.setCaseId("001-backend-java-ai");
        row.setStatus(status);
        row.setResults("[]");
        row.setTokens(100);
        row.setElapsedMs(1000L);
        return row;
    }

    @Test
    void createBaselineReusesRunWithoutRerun() {
        when(runMapper.selectById(1L)).thenReturn(run(false));
        when(caseMapper.selectList(any())).thenReturn(List.of(caseRow("PASS")));
        when(baselineMapper.selectOne(any())).thenReturn(null);
        when(catalog.combinedCorpusHash(any())).thenReturn("corpus");
        when(catalog.corpusHash("resume-analysis")).thenReturn("corpus");
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.getArgument(0, LlmEvalBaseline.class).setId(5L);
            return 1;
        }).when(baselineMapper).insert(any(LlmEvalBaseline.class));

        List<EvalBaselineResult> results = service()
                .createFromRun(new CreateEvalBaselineRequest(1L, "v3 发布前"));

        assertThat(results).containsExactly(new EvalBaselineResult("resume-analysis", true, 5L, null));
        ArgumentCaptor<LlmEvalBaseline> captor = ArgumentCaptor.forClass(LlmEvalBaseline.class);
        verify(baselineMapper).insert(captor.capture());
        LlmEvalBaseline saved = captor.getValue();
        assertThat(saved.getProviderKey()).isEqualTo("p");
        assertThat(saved.getModel()).isEqualTo("m");
        assertThat(saved.getPromptVersion()).isEqualTo("resume-analysis-v3");
        assertThat(saved.getPromptHash()).isEqualTo("hash");
        assertThat(saved.getCorpusHash()).isEqualTo("corpus");
        assertThat(saved.getLabel()).isEqualTo("v3 发布前");
        assertThat(saved.getCurrent()).isTrue();
        // 旧当前基线只取消标记，不删除历史
        verify(baselineMapper).update(isNull(), any());
    }

    @Test
    void sceneWithErrorIsSkipped() {
        when(runMapper.selectById(1L)).thenReturn(run(false));
        when(catalog.combinedCorpusHash(any())).thenReturn("corpus");
        when(caseMapper.selectList(any())).thenReturn(List.of(caseRow("ERROR")));

        List<EvalBaselineResult> results = service()
                .createFromRun(new CreateEvalBaselineRequest(1L, null));

        assertThat(results).containsExactly(new EvalBaselineResult("resume-analysis", false, null,
                "存在 1 个执行错误用例，已跳过"));
        verify(baselineMapper, never()).insert(any(LlmEvalBaseline.class));
    }

    @Test
    void partialRunCannotCreateBaseline() {
        when(runMapper.selectById(1L)).thenReturn(run(true));
        assertThatThrownBy(() -> service().createFromRun(new CreateEvalBaselineRequest(1L, null)))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).errorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void corpusChangedAfterRunCannotBecomeBaseline() {
        when(runMapper.selectById(1L)).thenReturn(run(false));
        when(catalog.combinedCorpusHash(any())).thenReturn("changed");

        assertThatThrownBy(() -> service().createFromRun(new CreateEvalBaselineRequest(1L, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("语料与当前不一致");
        verify(baselineMapper, never()).insert(any(LlmEvalBaseline.class));
    }

    @Test
    void activateOnlySwitchesCurrentFlag() {
        LlmEvalBaseline baseline = new LlmEvalBaseline();
        baseline.setId(7L);
        baseline.setScene("resume-analysis");
        baseline.setRunId(1L);
        baseline.setProviderKey("p");
        baseline.setModel("m");
        baseline.setPromptVersion("v3");
        baseline.setPromptHash("hash");
        baseline.setCorpusHash("corpus");
        baseline.setEngineVersion("llm-eval-v1");
        baseline.setParams("{}");
        baseline.setMetrics("{}");
        baseline.setCurrent(false);
        when(baselineMapper.selectById(7L)).thenReturn(baseline);

        var result = service().activate(7L);

        assertThat(result.current()).isTrue();
        assertThat(result.id()).isEqualTo(7L);
        verify(baselineMapper).update(isNull(), any());
        verify(baselineMapper).updateById(baseline);
    }
}
