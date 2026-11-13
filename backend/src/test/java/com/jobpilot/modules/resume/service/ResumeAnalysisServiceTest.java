package com.jobpilot.modules.resume.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import com.jobpilot.LambdaCacheSupport;
import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.common.llm.ChatMessage;
import com.jobpilot.common.llm.ChatResult;
import com.jobpilot.common.llm.LlmClient;
import com.jobpilot.common.llm.StreamChunk;
import com.jobpilot.infrastructure.llm.LlmCallLogger;
import com.jobpilot.infrastructure.llm.ProviderClientRegistry;
import com.jobpilot.modules.llm.entity.LlmGlobalSetting;
import com.jobpilot.modules.llm.mapper.LlmGlobalSettingMapper;
import com.jobpilot.modules.resume.entity.Resume;
import com.jobpilot.modules.resume.entity.ResumeAnalysis;
import com.jobpilot.modules.resume.mapper.ResumeAnalysisMapper;
import com.jobpilot.modules.resume.mapper.ResumeMapper;
import com.jobpilot.modules.resume.prompt.ResumePromptFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import reactor.core.publisher.Flux;

/** 异步分析状态机：入队拒绝回滚、启动分析、失败重试、报告组装与缺失字段降级。 */
@ExtendWith(MockitoExtension.class)
class ResumeAnalysisServiceTest {

    private static final String EMPTY_OPTIONAL_JSON = """
            {"technicalDepth":80,"projectQuality":70,"engineeringAbility":60,
             "expressionStructure":50,"jobMatchPotential":40}""";

    @Mock
    private ResumeMapper resumeMapper;

    @Mock
    private ResumeAnalysisMapper analysisMapper;

    @Mock
    private LlmGlobalSettingMapper globalMapper;

    @Mock
    private ProviderClientRegistry registry;

    @Mock
    private LlmCallLogger callLogger;

    @Mock
    private ResumePromptFactory prompts;

    @Mock
    private ThreadPoolTaskExecutor executor;

    @BeforeAll
    static void initLambdaCache() {
        LambdaCacheSupport.init(Resume.class);
    }

    private ResumeAnalysisService service() {
        return new ResumeAnalysisService(
                resumeMapper, analysisMapper, globalMapper, registry, callLogger, prompts, executor);
    }

    private static Resume resume(Long id, String status) {
        Resume entity = new Resume();
        entity.setId(id);
        entity.setAnalyzeStatus(status);
        entity.setResumeText("一段简历文本");
        entity.setRetryCount(0);
        return entity;
    }

    private static LlmGlobalSetting global(String providerKey, String model) {
        LlmGlobalSetting entity = new LlmGlobalSetting();
        entity.setDefaultProviderKey(providerKey);
        entity.setDefaultModel(model);
        return entity;
    }

    /** 同步执行 executor 任务，便于断言异步分支。 */
    private void runInline() {
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(executor).execute(any());
    }

    private static LlmClient clientReturning(String json) {
        return new LlmClient() {
            @Override
            public ChatResult chat(String model, List<ChatMessage> messages) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ChatResult chatJson(String model, List<ChatMessage> messages) {
                return new ChatResult(json, model, 1, 1, 2);
            }

            @Override
            public Flux<StreamChunk> stream(String model, List<ChatMessage> messages) {
                return Flux.empty();
            }

            @Override
            public List<String> listModels() {
                return List.of();
            }
        };
    }

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo(expected));
    }

    @Test
    void queueRejectionResetsToPending() {
        Resume resume = resume(1L, "PENDING");
        when(resumeMapper.selectById(1L)).thenReturn(resume);
        when(globalMapper.selectById(1)).thenReturn(global("fake", "m"));
        when(resumeMapper.update(isNull(), any())).thenReturn(1);
        doThrow(new RejectedExecutionException("full")).when(executor).execute(any());

        assertCode(() -> service().trigger(1L), ErrorCode.INTERNAL_ERROR);
        // 一次抢占 PROCESSING + 一次回退 PENDING
        verify(resumeMapper, times(2)).update(isNull(), any());
    }

    @Test
    void triggerRejectsMissingModelAndMissingResume() {
        when(resumeMapper.selectById(1L)).thenReturn(resume(1L, "PENDING"));
        when(globalMapper.selectById(1)).thenReturn(global("", ""));
        assertCode(() -> service().trigger(1L), ErrorCode.BAD_REQUEST);

        when(resumeMapper.selectById(9L)).thenReturn(null);
        assertCode(() -> service().trigger(9L), ErrorCode.NOT_FOUND);
    }

    @Test
    void autoTriggerSkipsWhenModelMissingOrGlobalQueryFails() {
        when(globalMapper.selectById(1)).thenReturn(null);
        service().autoTrigger(1L);
        verify(resumeMapper, never()).update(isNull(), any());

        when(globalMapper.selectById(1)).thenThrow(new RuntimeException("db down"));
        service().autoTrigger(1L);
        verify(resumeMapper, never()).update(isNull(), any());
    }

    @Test
    void autoTriggerQueueRejectionResetsToPending() {
        when(globalMapper.selectById(1)).thenReturn(global("p", "m"));
        when(resumeMapper.update(isNull(), any())).thenReturn(1);
        doThrow(new RejectedExecutionException("full")).when(executor).execute(any());

        service().autoTrigger(1L);

        verify(resumeMapper, times(2)).update(isNull(), any());
    }

    @Test
    void analysisSavesReportWhenOptionalFieldsMissing() {
        when(globalMapper.selectById(1)).thenReturn(global("p", "m"));
        when(resumeMapper.update(isNull(), any())).thenReturn(1);
        when(resumeMapper.selectById(1L)).thenReturn(resume(1L, "PROCESSING"));
        when(registry.get("p")).thenReturn(clientReturning(EMPTY_OPTIONAL_JSON));
        when(prompts.system()).thenReturn("system");
        when(prompts.user(anyString())).thenReturn("user");
        when(analysisMapper.selectOne(any())).thenReturn(null);
        runInline();

        service().autoTrigger(1L);

        verify(analysisMapper).insert(any(ResumeAnalysis.class));
        verify(callLogger).log(any(), any(), any(), any(), any(), any(), anyLong(), eq(true), isNull());
    }

    @Test
    void analysisMissingScoreFailsAfterRetries() {
        when(globalMapper.selectById(1)).thenReturn(global("p", "m"));
        when(resumeMapper.update(isNull(), any())).thenReturn(1);
        when(resumeMapper.selectById(1L)).thenReturn(resume(1L, "PROCESSING"));
        when(registry.get("p")).thenReturn(clientReturning("{\"projectQuality\":70}"));
        when(prompts.system()).thenReturn("system");
        when(prompts.user(anyString())).thenReturn("user");
        runInline();

        service().autoTrigger(1L);

        // 抢占 1 次 + 首次 + 3 次重试的 4 次状态落库
        verify(resumeMapper, times(5)).update(isNull(), any());
        verify(analysisMapper, never()).insert(any(ResumeAnalysis.class));
    }

    @Test
    void analysisStopsWhenResumeIsGoneOrNotProcessing() {
        when(globalMapper.selectById(1)).thenReturn(global("p", "m"));
        when(resumeMapper.update(isNull(), any())).thenReturn(1);
        when(resumeMapper.selectById(1L)).thenReturn(null);
        runInline();
        service().autoTrigger(1L);
        verify(registry, never()).get(anyString());

        when(resumeMapper.selectById(2L)).thenReturn(resume(2L, "COMPLETED"));
        service().autoTrigger(2L);
        verify(registry, never()).get(anyString());
    }

    @Test
    void analysisMarksFailedWhenModelGoneOrClientCreationFails() {
        when(resumeMapper.update(isNull(), any())).thenReturn(1);
        when(resumeMapper.selectById(1L)).thenReturn(resume(1L, "PROCESSING"));
        when(globalMapper.selectById(1)).thenReturn(global("p", "m"), (LlmGlobalSetting) null);
        runInline();

        service().autoTrigger(1L);
        verify(resumeMapper, times(2)).update(isNull(), any());

        when(globalMapper.selectById(1)).thenReturn(global("p", "m"));
        when(registry.get("p")).thenThrow(new RuntimeException("x".repeat(600)));
        service().autoTrigger(1L);
        verify(resumeMapper, times(4)).update(isNull(), any());
    }

    @Test
    void analysisStopsWhenResumeDeletedDuringRetryLoop() {
        when(globalMapper.selectById(1)).thenReturn(global("p", "m"));
        when(resumeMapper.update(isNull(), any())).thenReturn(1);
        when(registry.get("p")).thenReturn(clientReturning(EMPTY_OPTIONAL_JSON));
        // 首次进入 worker 正常，进入重试循环时已删除
        when(resumeMapper.selectById(1L)).thenReturn(resume(1L, "PROCESSING"), (Resume) null);
        runInline();

        service().autoTrigger(1L);

        // 只有抢占 PROCESSING，没有 COMPLETED / FAILED 落库
        verify(resumeMapper, times(1)).update(isNull(), any());
        verify(callLogger, never()).log(any(), any(), any(), any(), any(), any(), anyLong(),
                org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    @Test
    void statusBuildsReportAndDegradesMissingFields() {
        Resume resume = resume(1L, "COMPLETED");
        ResumeAnalysis valid = analysis("""
                {"name":"张三","yearsOfExperience":3,"techDirection":"Java",
                 "currentMonthlySalary":18000,"expectedMonthlySalary":25000}""");
        valid.setStrengths("not-json");
        valid.setWeaknesses(" ");
        valid.setSuggestions("[]");
        valid.setCareerDirections("[]");

        ResumeAnalysis blankBasic = analysis("");
        ResumeAnalysis brokenBasic = analysis("{bad");

        when(resumeMapper.selectById(1L)).thenReturn(resume, resume, resume);
        when(analysisMapper.selectOne(any())).thenReturn(valid, blankBasic, brokenBasic);

        assertThat(service().status(1L).report().basicInfo().currentMonthlySalary()).isEqualTo(18000);
        assertThat(service().status(1L).report().basicInfo()).isNull();
        assertThat(service().status(1L).report().basicInfo()).isNull();
    }

    private static ResumeAnalysis analysis(String basicInfo) {
        ResumeAnalysis entity = new ResumeAnalysis();
        entity.setResumeId(1L);
        entity.setOverallScore(70);
        entity.setScoreTechnicalDepth(80);
        entity.setScoreProjectQuality(70);
        entity.setScoreEngineeringAbility(60);
        entity.setScoreExpressionStructure(50);
        entity.setScoreJobMatchPotential(40);
        entity.setBasicInfo(basicInfo);
        entity.setStrengths("[]");
        entity.setWeaknesses("[]");
        entity.setSuggestions("[]");
        entity.setCareerDirections("[]");
        entity.setLlmProvider("p");
        entity.setLlmModel("m");
        return entity;
    }
}
