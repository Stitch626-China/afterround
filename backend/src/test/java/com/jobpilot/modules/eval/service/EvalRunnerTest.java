package com.jobpilot.modules.eval.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.jobpilot.common.llm.ChatResult;
import com.jobpilot.common.llm.LlmClient;
import com.jobpilot.common.llm.LlmException;
import com.jobpilot.infrastructure.llm.LlmCallLogger;
import com.jobpilot.modules.eval.core.EvalSceneCatalog;
import com.jobpilot.modules.eval.entity.LlmEvalRun;
import com.jobpilot.modules.eval.entity.LlmEvalRunCase;
import com.jobpilot.modules.eval.mapper.LlmEvalRunCaseMapper;
import com.jobpilot.modules.eval.mapper.LlmEvalRunMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** worker 逐用例落库、token 埋点与连续 3 次上游故障熔断。 */
class EvalRunnerTest {

    private static final String ANALYSIS_JSON = """
            {"technicalDepth":80,"projectQuality":80,"engineeringAbility":80,
             "expressionStructure":80,"jobMatchPotential":80,
             "basicInfo":{"name":"张三","yearsOfExperience":3,"education":"本科","currentPosition":"Java 后端"},
             "strengths":[{"point":"性能优化","quote":"优化了接口性能"}],
             "weaknesses":[{"point":"缺少量化","quote":"负责接口开发"}],
             "suggestions":[{"category":"表达","detail":"补充指标"}],
             "careerDirections":[{"direction":"Java 后端开发","matchReason":"匹配",
              "skillGaps":[],"interviewFocus":[]}]}
            """;

    private final LlmEvalRunMapper runMapper = mock(LlmEvalRunMapper.class);
    private final LlmEvalRunCaseMapper caseMapper = mock(LlmEvalRunCaseMapper.class);
    private final EvalClientFactory clientFactory = mock(EvalClientFactory.class);
    private final LlmCallLogger callLogger = mock(LlmCallLogger.class);
    private final EvalSceneCatalog catalog = new EvalSceneCatalog();
    private final EvalRunner runner = new EvalRunner(runMapper, caseMapper, catalog, clientFactory, callLogger);

    @BeforeAll
    static void initLambdaCache() {
        com.jobpilot.LambdaCacheSupport.init(LlmEvalRun.class, LlmEvalRunCase.class);
    }

    private static LlmEvalRun run() {
        LlmEvalRun run = new LlmEvalRun();
        run.setId(1L);
        run.setStatus("PENDING");
        run.setScenes("[\"resume-analysis\"]");
        run.setModelSnapshot("[{\"scene\":\"resume-analysis\",\"providerKey\":\"p\",\"model\":\"m\"}]");
        run.setPromptSnapshot("[{\"scene\":\"resume-analysis\",\"promptKey\":\"resume-analysis\","
                + "\"promptVersion\":\"v\",\"promptHash\":\"h\",\"system\":\"系统\","
                + "\"user\":\"请分析：{{resumeText}}\"}]");
        run.setParams("{\"repeat\":1}");
        run.setCancelRequested(false);
        return run;
    }

    private static LlmEvalRunCase caseRow(long id, String caseId) {
        LlmEvalRunCase row = new LlmEvalRunCase();
        row.setId(id);
        row.setRunId(1L);
        row.setScene("resume-analysis");
        row.setCaseId(caseId);
        row.setStatus("PENDING");
        return row;
    }

    private void bindCaseRows(List<LlmEvalRunCase> rows) {
        when(caseMapper.selectList(any())).thenReturn(rows);
        doAnswer(invocation -> {
            LlmEvalRunCase update = invocation.getArgument(0);
            rows.stream().filter(row -> row.getId().equals(update.getId())).findFirst().ifPresent(row -> {
                row.setStatus(update.getStatus());
                row.setL1Passed(update.getL1Passed());
                row.setResults(update.getResults());
                row.setTokens(update.getTokens());
                row.setElapsedMs(update.getElapsedMs());
                row.setError(update.getError());
            });
            return 1;
        }).when(caseMapper).updateById(any(LlmEvalRunCase.class));
    }

    @Test
    void writesCaseResultAndTokens() {
        when(runMapper.selectById(1L)).thenReturn(run());
        LlmClient client = mock(LlmClient.class);
        when(client.chatJson(anyString(), anyList()))
                .thenReturn(new ChatResult(ANALYSIS_JSON, "m", 100, 200, 300));
        when(clientFactory.get("p")).thenReturn(client);
        LlmEvalRunCase row = caseRow(10L, "001-backend-java-ai");
        bindCaseRows(List.of(row));

        runner.run(1L);

        assertThat(row.getStatus()).isIn("PASS", "FAIL");
        assertThat(row.getResults()).contains("L1");
        assertThat(row.getTokens()).isEqualTo(300);
        assertThat(row.getElapsedMs()).isNotNull();
        verify(callLogger).log(Mockito.eq("EVAL_RESUME_ANALYZE"), Mockito.eq("p"), Mockito.eq("m"),
                Mockito.isNull(), Mockito.isNull(), Mockito.eq(300), Mockito.anyLong(),
                Mockito.eq(!"ERROR".equals(row.getStatus())), Mockito.any());
    }

    @Test
    void consecutiveProviderErrorsAbortRun() {
        when(runMapper.selectById(1L)).thenReturn(run());
        LlmClient client = mock(LlmClient.class);
        when(client.chatJson(anyString(), anyList())).thenThrow(new LlmException(502, "上游服务返回 HTTP 502"));
        when(clientFactory.get("p")).thenReturn(client);
        List<LlmEvalRunCase> rows = List.of(caseRow(10L, "001-backend-java-ai"),
                caseRow(11L, "002-ai-rag-platform"), caseRow(12L, "003-newgrad-java-intern"),
                caseRow(13L, "004-sre-cloud-native"));
        bindCaseRows(rows);

        runner.run(1L);

        assertThat(rows.subList(0, 3)).extracting(LlmEvalRunCase::getStatus).containsOnly("ERROR");
        assertThat(rows.get(3).getStatus()).isEqualTo("PENDING");
        // 熔断前只跑 3 个用例；每个用例结构化输出内部最多 3 次
        verify(client, times(9)).chatJson(anyString(), anyList());
    }
}
