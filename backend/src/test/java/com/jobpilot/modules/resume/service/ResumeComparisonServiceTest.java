package com.jobpilot.modules.resume.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;

import com.jobpilot.LambdaCacheSupport;
import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.common.llm.ChatResult;
import com.jobpilot.common.llm.LlmClient;
import com.jobpilot.infrastructure.llm.LlmCallLogger;
import com.jobpilot.infrastructure.llm.ProviderClientRegistry;
import com.jobpilot.modules.llm.service.LlmSceneService;
import com.jobpilot.modules.resume.dto.CreateResumeComparisonRequest;
import com.jobpilot.modules.resume.entity.Resume;
import com.jobpilot.modules.resume.entity.ResumeAnalysis;
import com.jobpilot.modules.resume.mapper.ResumeAnalysisMapper;
import com.jobpilot.modules.resume.mapper.ResumeMapper;
import com.jobpilot.modules.resume.prompt.ResumeComparisonPromptFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 简历对比：本地差异计算、模型/Prompt 版本标注、LLM 失败降级。 */
@ExtendWith(MockitoExtension.class)
class ResumeComparisonServiceTest {

    @Mock
    private ResumeMapper resumeMapper;
    @Mock
    private ResumeAnalysisMapper analysisMapper;
    @Mock
    private LlmSceneService sceneService;
    @Mock
    private ProviderClientRegistry registry;
    @Mock
    private LlmCallLogger callLogger;

    @BeforeAll
    static void initLambdaCache() {
        LambdaCacheSupport.init(Resume.class, ResumeAnalysis.class);
    }

    private ResumeComparisonService service() {
        return new ResumeComparisonService(resumeMapper, analysisMapper, sceneService, registry,
                callLogger, new ResumeComparisonPromptFactory());
    }

    private static Resume resume(long id) {
        Resume resume = new Resume();
        resume.setId(id);
        resume.setFileName("resume-" + id + ".pdf");
        resume.setAnalyzeStatus("COMPLETED");
        return resume;
    }

    private static ResumeAnalysis analysis(int overall, int technical, String strengths, String weaknesses,
                                           String directions, String promptVersion) {
        ResumeAnalysis analysis = new ResumeAnalysis();
        analysis.setResumeId(1L);
        analysis.setOverallScore(overall);
        analysis.setScoreTechnicalDepth(technical);
        analysis.setScoreProjectQuality(70);
        analysis.setScoreEngineeringAbility(70);
        analysis.setScoreExpressionStructure(70);
        analysis.setScoreJobMatchPotential(70);
        analysis.setStrengths(strengths);
        analysis.setWeaknesses(weaknesses);
        analysis.setCareerDirections(directions);
        analysis.setLlmProvider("p");
        analysis.setLlmModel("m");
        analysis.setPromptVersion(promptVersion);
        analysis.setUpdatedAt(OffsetDateTime.now());
        return analysis;
    }

    @Test
    void rejectsSameResumeMissingResumeAndUnfinishedAnalysis() {
        assertCode(() -> service().compare(new CreateResumeComparisonRequest(1L, 1L)), ErrorCode.BAD_REQUEST);

        when(resumeMapper.selectById(1L)).thenReturn(null);
        assertCode(() -> service().compare(new CreateResumeComparisonRequest(1L, 2L)), ErrorCode.NOT_FOUND);

        Resume pending = resume(1L);
        pending.setAnalyzeStatus("PROCESSING");
        when(resumeMapper.selectById(1L)).thenReturn(pending);
        assertCode(() -> service().compare(new CreateResumeComparisonRequest(1L, 2L)),
                ErrorCode.ANALYSIS_NOT_COMPLETED);
    }

    @Test
    void computesLocalDiffAndAddsLlmSummary() {
        when(resumeMapper.selectById(1L)).thenReturn(resume(1L));
        when(resumeMapper.selectById(2L)).thenReturn(resume(2L));
        when(analysisMapper.selectOne(any())).thenReturn(
                analysis(70, 60,
                        "[{\"quote\":\"旧项目\",\"comment\":\"普通\"}]",
                        "[{\"quote\":\"缺少量化\",\"comment\":\"无数据\"}]",
                        "[{\"direction\":\"Java 后端\",\"matchReason\":\"匹配\",\"skillGaps\":[],\"interviewFocus\":[]}]",
                        "resume-analysis-v1"),
                analysis(80, 75,
                        "[{\"quote\":\"新增项目\",\"comment\":\"亮点\"}]",
                        "[{\"quote\":\"缺少量化\",\"comment\":\"无数据\"}]",
                        "[{\"direction\":\"Go 后端\",\"matchReason\":\"匹配\",\"skillGaps\":[],\"interviewFocus\":[]}]",
                        "resume-analysis-v1"));
        when(sceneService.resolveGlobal()).thenReturn(new LlmSceneService.SceneModel("p", "m"));
        LlmClient client = org.mockito.Mockito.mock(LlmClient.class);
        when(registry.get("p")).thenReturn(client);
        when(client.chatJson(any(), anyList())).thenReturn(
                new ChatResult("{\"summary\":\"新版简历在技术深度上有明显提升\"}", "m", 10, 20, 30));

        var result = service().compare(new CreateResumeComparisonRequest(1L, 2L));

        assertThat(result.overallScoreDelta()).isEqualTo(10);
        assertThat(result.dimensionDeltas()).extracting("key", "delta")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("technicalDepth", 15),
                        org.assertj.core.groups.Tuple.tuple("projectQuality", 0),
                        org.assertj.core.groups.Tuple.tuple("engineeringAbility", 0),
                        org.assertj.core.groups.Tuple.tuple("expressionStructure", 0),
                        org.assertj.core.groups.Tuple.tuple("jobMatchPotential", 0));
        assertThat(result.strengthsAdded()).extracting("quote").containsExactly("新增项目");
        assertThat(result.strengthsRemoved()).extracting("quote").containsExactly("旧项目");
        assertThat(result.weaknessesAdded()).isEmpty();
        assertThat(result.directionsAdded()).containsExactly("Go 后端");
        assertThat(result.directionsRemoved()).containsExactly("Java 后端");
        assertThat(result.summary().content()).contains("技术深度");
        assertThat(result.summary().degraded()).isFalse();
        assertThat(result.summary().modelMismatch()).isFalse();
        assertThat(result.summary().promptVersionMismatch()).isFalse();
    }

    @Test
    void degradesToLocalResultWhenLlmUnavailable() {
        when(resumeMapper.selectById(1L)).thenReturn(resume(1L));
        when(resumeMapper.selectById(2L)).thenReturn(resume(2L));
        when(analysisMapper.selectOne(any())).thenReturn(
                analysis(70, 60, "[]", "[]", "[]", null),
                analysis(75, 70, "[]", "[]", "[]", "resume-analysis-v1"));
        when(sceneService.resolveGlobal()).thenThrow(BizException.of(ErrorCode.BAD_REQUEST, "未配置默认模型"));

        var result = service().compare(new CreateResumeComparisonRequest(1L, 2L));

        assertThat(result.overallScoreDelta()).isEqualTo(5);
        assertThat(result.summary().degraded()).isTrue();
        assertThat(result.summary().content()).isNull();
        assertThat(result.summary().degradeReason()).contains("未配置默认模型");
        // 一方未记录 Prompt 版本时保守标注不同
        assertThat(result.summary().promptVersionMismatch()).isTrue();
        verify(callLogger).log(any(), any(), any(), any(), any(), any(), anyLong(),
                any(Boolean.class), any());
    }

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo(expected));
    }
}
