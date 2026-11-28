package com.jobpilot.modules.eval.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;

import com.jobpilot.modules.eval.dto.EvalCompareCase;
import com.jobpilot.modules.eval.dto.EvalRunCompare;
import com.jobpilot.modules.eval.entity.LlmEvalBaseline;
import com.jobpilot.modules.eval.entity.LlmEvalRun;
import com.jobpilot.modules.eval.entity.LlmEvalRunCase;
import com.jobpilot.modules.eval.mapper.LlmEvalBaselineMapper;
import com.jobpilot.modules.eval.mapper.LlmEvalRunCaseMapper;
import com.jobpilot.modules.eval.mapper.LlmEvalRunMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 4 维对比：修复 / 新增失败 / 分数漂移、场景维度不适用、不可比原因。 */
@ExtendWith(MockitoExtension.class)
class EvalCompareServiceTest {

    @Mock
    private LlmEvalRunMapper runMapper;
    @Mock
    private LlmEvalRunCaseMapper caseMapper;
    @Mock
    private LlmEvalBaselineMapper baselineMapper;

    @BeforeAll
    static void initLambdaCache() {
        com.jobpilot.LambdaCacheSupport.init(LlmEvalRun.class, LlmEvalRunCase.class, LlmEvalBaseline.class);
    }

    private EvalCompareService service() {
        return new EvalCompareService(runMapper, caseMapper, baselineMapper);
    }

    private static LlmEvalRun run(long id, String status, boolean partial, String scene, String model) {
        LlmEvalRun run = new LlmEvalRun();
        run.setId(id);
        run.setStatus(status);
        run.setPartial(partial);
        run.setScenes("[\"" + scene + "\"]");
        run.setModelSnapshot("[{\"scene\":\"" + scene + "\",\"providerKey\":\"p\",\"model\":\"" + model + "\"}]");
        run.setPromptSnapshot("[{\"scene\":\"" + scene + "\",\"promptKey\":\"resume-analysis\","
                + "\"promptVersion\":\"v3\",\"promptHash\":\"h\",\"system\":\"\",\"user\":\"\"}]");
        run.setCorpusHash("corpus");
        run.setEngineVersion("llm-eval-v1");
        run.setParams("{\"repeat\":1}");
        return run;
    }

    private static LlmEvalRunCase row(long id, String caseId, String status, String scores, String topics,
                                      int tokens) {
        LlmEvalRunCase row = new LlmEvalRunCase();
        row.setId(id);
        row.setRunId(1L);
        row.setScene("resume-analysis");
        row.setCaseId(caseId);
        row.setStatus(status);
        row.setScores(scores);
        row.setTopics(topics);
        row.setLossTags("[]");
        row.setResults(status.equals("ERROR") ? "[]" : "[{\"layer\":\"L2\",\"name\":\"x\",\"passed\":true,"
                + "\"detail\":\"ok\"}]");
        row.setTokens(tokens);
        row.setElapsedMs(1000L);
        return row;
    }

    @Test
    void comparesFixedAndDriftedCases() {
        LlmEvalRun left = run(1L, "COMPLETED", false, "resume-analysis", "m");
        LlmEvalRun right = run(2L, "COMPLETED", false, "resume-analysis", "m");
        when(runMapper.selectById(1L)).thenReturn(left);
        when(runMapper.selectById(2L)).thenReturn(right);
        when(baselineMapper.selectOne(any())).thenReturn(null);
        List<LlmEvalRunCase> leftRows = List.of(
                row(10L, "001", "PASS", "{\"overall#0\":80}", "[]", 100),
                row(11L, "002", "FAIL", "{\"overall#0\":70}", "[]", 100));
        List<LlmEvalRunCase> rightRows = List.of(
                row(20L, "001", "PASS", "{\"overall#0\":95}", "[]", 150),
                row(21L, "002", "PASS", "{\"overall#0\":72}", "[]", 150));
        when(caseMapper.selectList(any())).thenReturn(leftRows, rightRows);

        EvalRunCompare compare = service().compare(1L, 2L);

        assertThat(compare.comparable()).isTrue();
        assertThat(compare.applicableDimensions()).contains("QUALITY", "SCORE_DRIFT", "COST", "LATENCY")
                .doesNotContain("TAG_CHANGE");
        assertThat(compare.regression().fixedCases()).isEqualTo(1);
        assertThat(compare.regression().scoreDriftCases()).isEqualTo(1);
        assertThat(compare.regression().newFailedCases()).isZero();
        EvalCompareCase fixed = compare.cases().stream().filter(item -> item.caseId().equals("002")).findFirst()
                .orElseThrow();
        assertThat(fixed.changes()).contains("FIXED");
        EvalCompareCase drifted = compare.cases().stream().filter(item -> item.caseId().equals("001")).findFirst()
                .orElseThrow();
        assertThat(drifted.changes()).contains("SCORE_DRIFT");
        assertThat(drifted.scoreDrift()).isEqualTo(15.0);
        assertThat(compare.cost().delta()).isEqualTo(100L);
    }

    @Test
    void differentModelIsNotComparable() {
        when(runMapper.selectById(1L)).thenReturn(run(1L, "COMPLETED", false, "resume-analysis", "m1"));
        when(runMapper.selectById(2L)).thenReturn(run(2L, "COMPLETED", false, "resume-analysis", "m2"));
        when(caseMapper.selectList(any())).thenReturn(List.of(), List.of());

        EvalRunCompare compare = service().compare(1L, 2L);

        assertThat(compare.comparable()).isFalse();
        assertThat(compare.reasons()).anyMatch(reason -> reason.contains("模型不同"));
        assertThat(compare.quality()).isNull();
    }

    @Test
    void cancelledOrPartialRunIsNotComparable() {
        when(runMapper.selectById(1L)).thenReturn(run(1L, "COMPLETED", false, "resume-analysis", "m"));
        when(runMapper.selectById(2L)).thenReturn(run(2L, "CANCELLED", true, "resume-analysis", "m"));
        when(caseMapper.selectList(any())).thenReturn(List.of(), List.of());

        EvalRunCompare compare = service().compare(1L, 2L);

        assertThat(compare.comparable()).isFalse();
        assertThat(compare.reasons()).anyMatch(reason -> reason.contains("右侧运行未完成"));
    }

    @Test
    void jdSceneHasNoScoreOrTagDimension() {
        LlmEvalRun left = run(1L, "COMPLETED", false, "jd-parse", "m");
        LlmEvalRun right = run(2L, "COMPLETED", false, "jd-parse", "m");
        when(runMapper.selectById(1L)).thenReturn(left);
        when(runMapper.selectById(2L)).thenReturn(right);
        when(caseMapper.selectList(any())).thenReturn(List.of(), List.of());

        assertThat(service().compare(1L, 2L).applicableDimensions())
                .containsExactly("QUALITY", "COST", "LATENCY");
    }

    @Test
    void jdScoreDriftIsNotCountedWhenDimensionNotApplicable() {
        LlmEvalRun left = run(1L, "COMPLETED", false, "jd-parse", "m");
        LlmEvalRun right = run(2L, "COMPLETED", false, "jd-parse", "m");
        when(runMapper.selectById(1L)).thenReturn(left);
        when(runMapper.selectById(2L)).thenReturn(right);
        LlmEvalRunCase leftRow = row(10L, "001", "PASS", "{\"salaryMin#0\":20}", "[]", 100);
        leftRow.setScene("jd-parse");
        LlmEvalRunCase rightRow = row(20L, "001", "PASS", "{\"salaryMin#0\":40}", "[]", 100);
        rightRow.setScene("jd-parse");
        when(caseMapper.selectList(any())).thenReturn(List.of(leftRow), List.of(rightRow));

        EvalRunCompare compare = service().compare(1L, 2L);

        assertThat(compare.regression().scoreDriftCases()).isZero();
        assertThat(compare.cases().get(0).changes()).containsExactly("UNCHANGED");
        assertThat(compare.cases().get(0).scoreDrift()).isNull();
    }

    @Test
    void legacyProviderComparesByModelWithWarning() {
        LlmEvalRun left = run(1L, "COMPLETED", false, "resume-analysis", "m");
        left.setModelSnapshot("[{\"scene\":\"resume-analysis\",\"providerKey\":\"legacy\",\"model\":\"m\"}]");
        LlmEvalRun right = run(2L, "COMPLETED", false, "resume-analysis", "m");
        when(runMapper.selectById(1L)).thenReturn(left);
        when(runMapper.selectById(2L)).thenReturn(right);
        when(caseMapper.selectList(any())).thenReturn(List.of(), List.of());

        EvalRunCompare compare = service().compare(1L, 2L);

        assertThat(compare.comparable()).isTrue();
        assertThat(compare.warnings()).contains("历史基线 Provider 未知，按模型比较");
    }
}
