package com.jobpilot.modules.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.LambdaCacheSupport;
import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.common.llm.ChatResult;
import com.jobpilot.common.llm.LlmClient;
import com.jobpilot.infrastructure.asr.AsrClient;
import com.jobpilot.infrastructure.llm.LlmCallLogger;
import com.jobpilot.infrastructure.llm.ProviderClientRegistry;
import com.jobpilot.infrastructure.storage.FileStorage;
import com.jobpilot.modules.application.entity.JobApplication;
import com.jobpilot.modules.application.mapper.JobApplicationMapper;
import com.jobpilot.modules.company.entity.Company;
import com.jobpilot.modules.company.mapper.CompanyMapper;
import com.jobpilot.modules.job.entity.JobPosition;
import com.jobpilot.modules.job.mapper.JobPositionMapper;
import com.jobpilot.modules.llm.dto.LlmScene;
import com.jobpilot.modules.llm.service.LlmSceneService;
import com.jobpilot.modules.review.dto.ImprovementActionDraftResponse;
import com.jobpilot.modules.review.dto.ReviewAnalysisResponse;
import com.jobpilot.modules.review.dto.ReviewAnalysisUpdateRequest;
import com.jobpilot.modules.review.dto.ReviewQuestionResponse;
import com.jobpilot.modules.review.dto.ReviewSummaryResponse;
import com.jobpilot.modules.review.dto.ReviewTagResponse;
import com.jobpilot.modules.review.dto.UpdateReviewSessionRequest;
import com.jobpilot.modules.review.entity.InterviewEvent;
import com.jobpilot.modules.review.entity.ReviewAnalysis;
import com.jobpilot.modules.review.entity.ReviewSession;
import com.jobpilot.modules.review.mapper.InterviewEventMapper;
import com.jobpilot.modules.review.mapper.ReviewAnalysisMapper;
import com.jobpilot.modules.review.mapper.ReviewSessionMapper;
import com.jobpilot.modules.review.prompt.ReviewPromptFactory;
import com.jobpilot.modules.resume.entity.Resume;
import com.jobpilot.modules.resume.mapper.ResumeMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 复盘会话：输入校验、轮次唯一、异步分析、修正与确认、状态查询。 */
@ExtendWith(MockitoExtension.class)
class ReviewSessionServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private ReviewSessionMapper sessionMapper;
    @Mock
    private ReviewAnalysisMapper analysisMapper;
    @Mock
    private InterviewEventMapper eventMapper;
    @Mock
    private InterviewEventService eventService;
    @Mock
    private ImprovementActionService actionService;
    @Mock
    private JobApplicationMapper applicationMapper;
    @Mock
    private JobPositionMapper jobMapper;
    @Mock
    private CompanyMapper companyMapper;
    @Mock
    private ResumeMapper resumeMapper;
    @Mock
    private LlmSceneService sceneService;
    @Mock
    private ProviderClientRegistry registry;
    @Mock
    private LlmCallLogger callLogger;
    @Mock
    private ReviewPromptFactory prompts;
    @Mock
    private AsrClient asrClient;
    @Mock
    private FileStorage storage;
    @Mock
    private ThreadPoolTaskExecutor executor;
    @Mock
    private ThreadPoolTaskExecutor asrExecutor;
    @Mock
    private LlmClient client;

    @BeforeAll
    static void initLambdaCache() {
        LambdaCacheSupport.init(ReviewSession.class, ReviewAnalysis.class, InterviewEvent.class,
                JobApplication.class, JobPosition.class, Company.class);
    }

    private ReviewSessionService service() {
        return new ReviewSessionService(sessionMapper, analysisMapper, eventMapper, eventService, actionService,
                applicationMapper, jobMapper, companyMapper, resumeMapper, sceneService, registry, callLogger,
                prompts, asrClient, storage, executor, asrExecutor);
    }

    private static ReviewSession session(long id, long eventId) {
        ReviewSession session = new ReviewSession();
        session.setId(id);
        session.setEventId(eventId);
        session.setInputType("TEXT");
        session.setTranscript("问：讲讲 JVM 内存模型。答：分为堆、方法区、栈。");
        session.setAsrStatus("SKIPPED");
        session.setAnalyzeStatus("COMPLETED");
        session.setStatus("READY");
        session.setAsrRetryCount(0);
        session.setAnalyzeRetryCount(0);
        session.setConfirmed(false);
        session.setCreatedAt(OffsetDateTime.now());
        session.setUpdatedAt(OffsetDateTime.now());
        return session;
    }

    private static InterviewEvent event(long id, long applicationId) {
        InterviewEvent event = new InterviewEvent();
        event.setId(id);
        event.setApplicationId(applicationId);
        event.setRoundNo(1);
        event.setRoundType("TECH");
        event.setInterviewTime(OffsetDateTime.now());
        event.setResult("PENDING");
        return event;
    }

    private void stubLookups() {
        when(eventMapper.selectByIds(anyList())).thenReturn(List.of(event(20L, 7L)));
        JobApplication application = new JobApplication();
        application.setId(7L);
        application.setJobPositionId(1L);
        when(applicationMapper.selectByIds(anyList())).thenReturn(List.of(application));
        JobPosition job = new JobPosition();
        job.setId(1L);
        job.setCompanyId(2L);
        job.setTitle("Java 后端");
        when(jobMapper.selectByIds(anyList())).thenReturn(List.of(job));
        Company company = new Company();
        company.setId(2L);
        company.setName("字节跳动");
        when(companyMapper.selectByIds(anyList())).thenReturn(List.of(company));
    }

    private static LlmSceneService.SceneModel globalModel() {
        return new LlmSceneService.SceneModel("deepseek", "deepseek-flash");
    }

    @Test
    void createRejectsAudioAndEmptyInput() {
        org.springframework.mock.web.MockMultipartFile audio =
                new org.springframework.mock.web.MockMultipartFile("audio", "a.mp3", "audio/mpeg", new byte[]{1});
        assertCode(() -> service().create(audio, null, null, 7L, null, null, null, null, null, null, null, 1, "TECH", null, null, null, null),
                ErrorCode.BAD_REQUEST);
        assertCode(() -> service().create(null, "  ", null, 7L, null, null, null, null, null, null, null, 1, "TECH", null, null, null, null),
                ErrorCode.BAD_REQUEST);
    }

    @Test
    void createRejectsUnsupportedAudioType() {
        when(asrClient.configured()).thenReturn(true);
        org.springframework.mock.web.MockMultipartFile audio =
                new org.springframework.mock.web.MockMultipartFile("audio", "a.mp4", "video/mp4", new byte[]{1});
        assertCode(() -> service().create(audio, null, null, 7L, null, null, null, null, null, null, null, 1, "TECH", null, null, null, null),
                ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void createAudioStoresFileAndTranscribes() {
        when(asrClient.configured()).thenReturn(true);
        org.springframework.mock.web.MockMultipartFile audio =
                new org.springframework.mock.web.MockMultipartFile("audio", "a.mp3", "audio/mpeg",
                        new byte[]{1, 2});
        when(eventService.ensureEvent(eq(7L), isNull(), eq(1), eq("TECH"), isNull(), isNull(), isNull()))
                .thenReturn(event(20L, 7L));
        when(sessionMapper.selectCount(any())).thenReturn(0L);
        when(sessionMapper.insert(any(ReviewSession.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ReviewSession.class).setId(30L);
            return 1;
        });
        ReviewSession audioSession = session(30L, 20L);
        audioSession.setInputType("AUDIO");
        audioSession.setAsrStatus("PROCESSING");
        audioSession.setStatus("TRANSCRIBING");
        audioSession.setAudioStorageKey("reviews/x.mp3");
        audioSession.setAudioFileName("a.mp3");
        when(sessionMapper.selectById(30L)).thenReturn(audioSession);
        when(sessionMapper.update(any(), any())).thenReturn(1);
        when(storage.load("reviews/x.mp3")).thenReturn(new byte[]{1, 2});
        when(asrClient.transcribe(eq("a.mp3"), eq("audio/mpeg"), any(byte[].class))).thenReturn("转写文本");
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(asrExecutor).execute(any(Runnable.class));
        stubLookups();

        var response = service().create(audio, null, null, 7L, null, null, null, null, null, null, null, 1, "TECH", null, null, null, null);

        assertThat(response.inputType()).isEqualTo("AUDIO");
        verify(storage).save(startsWith("reviews/"), any(byte[].class));
        verify(asrClient).transcribe(eq("a.mp3"), eq("audio/mpeg"), any(byte[].class));
    }

    @Test
    void transcribeRetriesFailedAudioSession() {
        ReviewSession audioSession = session(30L, 20L);
        audioSession.setInputType("AUDIO");
        audioSession.setAsrStatus("FAILED");
        audioSession.setStatus("FAILED");
        audioSession.setAudioStorageKey("reviews/y.wav");
        audioSession.setAudioFileName("b.wav");
        when(sessionMapper.selectById(30L)).thenReturn(audioSession);
        when(asrClient.configured()).thenReturn(true);
        when(sessionMapper.update(any(), any())).thenAnswer(invocation -> {
            audioSession.setAsrStatus("PROCESSING");
            return 1;
        });
        when(storage.load("reviews/y.wav")).thenReturn(new byte[]{1});
        when(asrClient.transcribe(eq("b.wav"), eq("audio/wav"), any(byte[].class))).thenReturn("重试成功");
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(asrExecutor).execute(any(Runnable.class));
        stubLookups();

        service().transcribe(30L);

        verify(asrClient).transcribe(eq("b.wav"), eq("audio/wav"), any(byte[].class));
    }

    @Test
    void transcribeRejectsNonAudioAndFinished() {
        ReviewSession text = session(30L, 20L);
        when(sessionMapper.selectById(30L)).thenReturn(text);
        assertCode(() -> service().transcribe(30L), ErrorCode.BAD_REQUEST);

        ReviewSession done = session(30L, 20L);
        done.setInputType("AUDIO");
        done.setAsrStatus("COMPLETED");
        done.setAudioStorageKey("reviews/z.m4a");
        when(sessionMapper.selectById(31L)).thenReturn(done);
        assertCode(() -> service().transcribe(31L), ErrorCode.BAD_REQUEST);
    }

    @Test
    void deleteRemovesAudioFile() {
        ReviewSession audioSession = session(30L, 20L);
        audioSession.setAudioStorageKey("reviews/z.m4a");
        when(sessionMapper.selectById(30L)).thenReturn(audioSession);
        when(sessionMapper.deleteById(30L)).thenReturn(1);

        service().delete(30L);

        verify(storage).delete("reviews/z.m4a");
        verify(sessionMapper).deleteById(30L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void clearAudioDeletesFileAndBlanksAudioFields() {
        ReviewSession audioSession = session(30L, 20L);
        audioSession.setInputType("AUDIO");
        audioSession.setAudioStorageKey("reviews/a.mp3");
        audioSession.setAudioFileName("a.mp3");
        when(sessionMapper.selectById(30L)).thenReturn(audioSession);
        when(sessionMapper.update(isNull(), any())).thenReturn(1);

        service().clearAudio(30L);

        verify(storage).delete("reviews/a.mp3");
        ArgumentCaptor<Wrapper<ReviewSession>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(sessionMapper).update(isNull(), captor.capture());
        assertThat(((LambdaUpdateWrapper<?>) captor.getValue()).getSqlSet())
                .contains("audio_storage_key", "audio_file_name", "audio_size", "audio_duration_seconds");
    }

    @Test
    void clearAudioRejectsNonAudioAndClearedSessions() {
        when(sessionMapper.selectById(30L)).thenReturn(session(30L, 20L));
        assertCode(() -> service().clearAudio(30L), ErrorCode.BAD_REQUEST);

        ReviewSession cleared = session(31L, 20L);
        cleared.setInputType("AUDIO");
        when(sessionMapper.selectById(31L)).thenReturn(cleared);
        assertCode(() -> service().clearAudio(31L), ErrorCode.BAD_REQUEST);
        verify(storage, never()).delete(anyString());
    }

    @Test
    void clearAudioRejectsWhileProcessing() {
        ReviewSession transcribing = session(30L, 20L);
        transcribing.setInputType("AUDIO");
        transcribing.setAudioStorageKey("reviews/a.mp3");
        transcribing.setAsrStatus("PROCESSING");
        when(sessionMapper.selectById(30L)).thenReturn(transcribing);
        assertCode(() -> service().clearAudio(30L), ErrorCode.REVIEW_TASK_IN_PROGRESS);

        ReviewSession analyzing = session(31L, 20L);
        analyzing.setInputType("AUDIO");
        analyzing.setAudioStorageKey("reviews/b.mp3");
        analyzing.setAnalyzeStatus("PROCESSING");
        when(sessionMapper.selectById(31L)).thenReturn(analyzing);
        assertCode(() -> service().clearAudio(31L), ErrorCode.REVIEW_TASK_IN_PROGRESS);
        verify(storage, never()).delete(anyString());
    }

    @Test
    void clearAudioLosingRaceReportsAlreadyCleared() {
        ReviewSession audioSession = session(30L, 20L);
        audioSession.setInputType("AUDIO");
        audioSession.setAudioStorageKey("reviews/a.mp3");
        when(sessionMapper.selectById(30L)).thenReturn(audioSession);
        when(sessionMapper.update(isNull(), any())).thenReturn(0);

        assertCode(() -> service().clearAudio(30L), ErrorCode.BAD_REQUEST);
    }

    @Test
    void createPersistsTranscriptAndReturnsListItem() {
        when(eventService.ensureEvent(eq(7L), isNull(), eq(1), eq("TECH"), isNull(), isNull(), isNull()))
                .thenReturn(event(20L, 7L));
        when(sessionMapper.selectCount(any())).thenReturn(0L);
        when(sessionMapper.insert(any(ReviewSession.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ReviewSession.class).setId(30L);
            return 1;
        });
        when(sessionMapper.selectById(30L)).thenAnswer(invocation -> {
            ReviewSession created = session(30L, 20L);
            created.setInputType("TEXT");
            created.setStatus("UPLOADED");
            created.setAnalyzeStatus("PENDING");
            return created;
        });
        stubLookups();

        var response = service().create(null, " 问：JVM？答：堆与栈。 ", "备注", 7L, null, null, null, null, null, null, null, 1,
                "TECH", null, null, null, null);

        assertThat(response.id()).isEqualTo(30L);
        assertThat(response.inputType()).isEqualTo("TEXT");
        assertThat(response.status()).isEqualTo("UPLOADED");
        assertThat(response.analyzeStatus()).isEqualTo("PENDING");
        assertThat(response.companyName()).isEqualTo("字节跳动");
        assertThat(response.jobTitle()).isEqualTo("Java 后端");
        verify(executor, never()).execute(any(Runnable.class));
    }

    @Test
    void createLinksResumeToApplication() {
        when(eventService.ensureEvent(eq(7L), isNull(), eq(1), eq("TECH"), isNull(), isNull(), isNull()))
                .thenReturn(event(20L, 7L));
        when(sessionMapper.selectCount(any())).thenReturn(0L);
        Resume resume = new Resume();
        resume.setId(3L);
        when(resumeMapper.selectById(3L)).thenReturn(resume);
        JobApplication application = new JobApplication();
        application.setId(7L);
        application.setJobPositionId(1L);
        when(applicationMapper.selectById(7L)).thenReturn(application);
        when(sessionMapper.insert(any(ReviewSession.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ReviewSession.class).setId(30L);
            return 1;
        });
        when(sessionMapper.selectById(30L)).thenReturn(session(30L, 20L));
        stubLookups();

        service().create(null, "文本", null, 7L, null, null, null, null, null, null, 3L, 1, "TECH",
                null, null, null, null);

        ArgumentCaptor<JobApplication> captor = ArgumentCaptor.forClass(JobApplication.class);
        verify(applicationMapper).updateById(captor.capture());
        assertThat(captor.getValue().getResumeId()).isEqualTo(3L);
    }

    @Test
    void analyzePromptCarriesCompanyJobAndResumeContext() {
        ReviewSession session = session(30L, 20L);
        when(sessionMapper.selectById(30L)).thenReturn(session);
        when(sceneService.resolveGlobal()).thenReturn(globalModel());
        when(registry.get("deepseek")).thenReturn(client);
        when(eventMapper.selectById(20L)).thenReturn(event(20L, 7L));
        JobApplication application = new JobApplication();
        application.setId(7L);
        application.setJobPositionId(1L);
        application.setResumeId(3L);
        when(applicationMapper.selectById(7L)).thenReturn(application);
        JobPosition job = new JobPosition();
        job.setId(1L);
        job.setCompanyId(2L);
        job.setTitle("Java 后端");
        job.setRawText("JD 原文");
        when(jobMapper.selectById(1L)).thenReturn(job);
        Company company = new Company();
        company.setId(2L);
        company.setName("字节跳动");
        company.setIntro("公司介绍");
        when(companyMapper.selectById(2L)).thenReturn(company);
        Resume resume = new Resume();
        resume.setId(3L);
        resume.setResumeText("简历原文");
        when(resumeMapper.selectById(3L)).thenReturn(resume);
        when(client.chatJson(eq("deepseek-flash"), any())).thenThrow(new RuntimeException("stop"));
        when(sessionMapper.update(any(), any())).thenAnswer(invocation -> {
            session.setAnalyzeStatus("PROCESSING");
            return 1;
        });
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        stubLookups();

        service().analyze(30L);

        verify(prompts).user("字节跳动", "Java 后端", "TECH", null, "公司介绍", "JD 原文", "简历原文",
                session.getTranscript());
    }

    @Test
    void createRejectsDuplicateReviewAndRequiresSource() {
        when(eventService.ensureEvent(eq(7L), isNull(), eq(1), eq("TECH"), isNull(), isNull(), isNull()))
                .thenReturn(event(20L, 7L));
        when(sessionMapper.selectCount(any())).thenReturn(1L);
        assertCode(() -> service().create(null, "文本", null, 7L, null, null, null, null, null, null, null, 1, "TECH",
                        null, null, null, null),
                ErrorCode.REVIEW_ROUND_EXISTS);
    }

    @Test
    void analyzeRunsPipelineAndNormalizesOutput() throws Exception {
        ReviewSession session = session(30L, 20L);
        when(sessionMapper.selectById(30L)).thenReturn(session);
        when(sceneService.resolveGlobal()).thenReturn(globalModel());
        when(registry.get("deepseek")).thenReturn(client);
        when(client.chatJson(eq("deepseek-flash"), any())).thenReturn(new ChatResult("""
                {"questions":[{"questionIndex":1,"question":"讲讲 JVM 内存模型","answerSummary":"堆与栈",
                "score":82,"dimensions":[{"dimension":"depth","score":75},{"dimension":"unknown","score":90}],
                "topics":["jvm","unknown"],"lossTags":["shallow-depth"],"evidence":"原文","feedback":"可以",
                "referenceAnswer":"要点"}],
                "topics":[{"tag":"jvm","confidence":0.9,"evidence":"原文"}],
                "lossTags":[{"tag":"shallow-depth","confidence":0.7}],
                "summary":{"overall":"整体尚可","strengths":["基础"],"improvements":["深度"],
                "nextPreparation":["复习 GC"]},
                "actions":[{"action":"复习 GC","category":"知识点补强","reason":"深度不足"},
                {"action":"练习表达","category":null,"reason":null},
                {"action":"补充项目数据","category":"项目","reason":null},
                {"action":"第四条","category":null,"reason":null}]}
                """, "deepseek-flash", 100, 200, 300));
        when(sessionMapper.update(any(), any())).thenAnswer(invocation -> {
            session.setAnalyzeStatus("PROCESSING");
            return 1;
        });
        when(analysisMapper.insert(any(ReviewAnalysis.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ReviewAnalysis.class).setId(40L);
            return 1;
        });
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        stubLookups();

        service().analyze(30L);

        ArgumentCaptor<ReviewAnalysis> captor = ArgumentCaptor.forClass(ReviewAnalysis.class);
        verify(analysisMapper).insert(captor.capture());
        ReviewAnalysis saved = captor.getValue();
        assertThat(saved.getPromptVersion()).isEqualTo(ReviewPromptFactory.VERSION);
        assertThat(saved.getLlmProvider()).isEqualTo("deepseek");
        ReviewAnalysisResponse analysis = MAPPER.readValue(saved.getAnalysis(), ReviewAnalysisResponse.class);
        assertThat(analysis.questions()).hasSize(1);
        assertThat(analysis.questions().get(0).dimensions()).hasSize(1);
        assertThat(analysis.questions().get(0).topics()).containsExactly("jvm");
        assertThat(analysis.actions()).hasSize(3);
        verify(callLogger).log(eq("REVIEW_ANALYZE"), eq("deepseek"), eq("deepseek-flash"),
                eq(100), eq(200), eq(300), anyLong(), eq(true), isNull());
    }

    @Test
    void analyzeRejectsMissingModelAndIncompleteAsr() {
        ReviewSession audio = session(30L, 20L);
        audio.setInputType("AUDIO");
        audio.setAsrStatus("PENDING");
        when(sessionMapper.selectById(30L)).thenReturn(audio);
        assertCode(() -> service().analyze(30L), ErrorCode.REVIEW_TASK_IN_PROGRESS);

        ReviewSession text = session(30L, 20L);
        when(sessionMapper.selectById(30L)).thenReturn(text);
        assertCode(() -> service().analyze(30L), ErrorCode.BAD_REQUEST);
    }

    @Test
    void analyzeAllowsManualTextWhenAsrFailed() {
        ReviewSession audio = session(30L, 20L);
        audio.setInputType("AUDIO");
        audio.setAsrStatus("FAILED");
        audio.setTranscript("手动补充的转写文本");
        when(sessionMapper.selectById(30L)).thenReturn(audio);
        when(sceneService.resolveGlobal()).thenReturn(globalModel());
        when(registry.get("deepseek")).thenReturn(client);
        when(client.chatJson(eq("deepseek-flash"), any())).thenThrow(new RuntimeException("upstream down"));
        when(sessionMapper.update(any(), any())).thenAnswer(invocation -> {
            audio.setAnalyzeStatus("PROCESSING");
            return 1;
        });
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        stubLookups();

        service().analyze(30L);

        verify(executor).execute(any(Runnable.class));
        verify(callLogger, atLeastOnce()).log(eq("REVIEW_ANALYZE"), eq("deepseek"), eq("deepseek-flash"),
                isNull(), isNull(), isNull(), anyLong(), eq(false), anyString());
    }

    @Test
    void analyzePrefersReviewSceneBinding() {
        ReviewSession session = session(30L, 20L);
        when(sessionMapper.selectById(30L)).thenReturn(session);
        when(sceneService.optionalResolve(LlmScene.REVIEW_ANALYZE))
                .thenReturn(new LlmSceneService.SceneModel("scene-provider", "scene-model"));
        when(registry.get("scene-provider")).thenReturn(client);
        when(client.chatJson(eq("scene-model"), any())).thenThrow(new RuntimeException("upstream down"));
        when(sessionMapper.update(any(), any())).thenAnswer(invocation -> {
            session.setAnalyzeStatus("PROCESSING");
            return 1;
        });
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        stubLookups();

        service().analyze(30L);

        verify(callLogger, atLeastOnce()).log(eq("REVIEW_ANALYZE"), eq("scene-provider"), eq("scene-model"),
                isNull(), isNull(), isNull(), anyLong(), eq(false), anyString());
        verify(sceneService, never()).resolveGlobal();
    }

    @Test
    void updateRejectsProcessingAndChangesContext() {
        ReviewSession processing = session(30L, 20L);
        processing.setAnalyzeStatus("PROCESSING");
        when(sessionMapper.selectById(30L)).thenReturn(processing);
        assertCode(() -> service().update(30L, new UpdateReviewSessionRequest(null, null, null, null, null, null)),
                ErrorCode.REVIEW_TASK_IN_PROGRESS);

        ReviewSession idle = session(30L, 20L);
        when(sessionMapper.selectById(30L)).thenReturn(idle);
        when(eventMapper.selectById(20L)).thenReturn(event(20L, 7L));
        when(eventMapper.updateById(any(InterviewEvent.class))).thenReturn(1);
        when(sessionMapper.updateById(any(ReviewSession.class))).thenReturn(1);
        stubLookups();
        when(analysisMapper.selectOne(any())).thenReturn(null);

        var detail = service().update(30L, new UpdateReviewSessionRequest(
                MAPPER.getNodeFactory().textNode(" 修正后的转写 "), null,
                MAPPER.getNodeFactory().textNode(" 技术总监 "),
                MAPPER.getNodeFactory().textNode("HR"),
                MAPPER.getNodeFactory().textNode("ONSITE"),
                MAPPER.getNodeFactory().textNode("2026-11-20T10:00:00+08:00")));

        assertThat(detail.transcript()).isEqualTo("修正后的转写");
        verify(eventMapper).updateById(any(InterviewEvent.class));
        verify(sessionMapper).updateById(any(ReviewSession.class));
    }

    @Test
    void updateAnalysisRequiresReadyAndValidTags() throws Exception {
        ReviewSession session = session(30L, 20L);
        session.setAnalyzeStatus("PENDING");
        when(sessionMapper.selectById(30L)).thenReturn(session);
        assertCode(() -> service().updateAnalysis(30L, updateRequest("jvm", true)), ErrorCode.REVIEW_NOT_READY);

        session.setAnalyzeStatus("COMPLETED");
        ReviewAnalysis row = analysisRow();
        when(analysisMapper.selectOne(any())).thenReturn(row);
        assertCode(() -> service().updateAnalysis(30L, updateRequest("unknown-topic", true)),
                ErrorCode.BAD_REQUEST);
    }

    @Test
    void updateAnalysisConfirmedSyncsActions() {
        ReviewSession session = session(30L, 20L);
        when(sessionMapper.selectById(30L)).thenReturn(session);
        ReviewAnalysis row = analysisRow();
        when(analysisMapper.selectOne(any())).thenReturn(row);
        when(analysisMapper.updateById(any(ReviewAnalysis.class))).thenReturn(1);
        when(sessionMapper.updateById(any(ReviewSession.class))).thenReturn(1);
        when(eventMapper.selectById(20L)).thenReturn(event(20L, 7L));
        stubLookups();

        var detail = service().updateAnalysis(30L, updateRequest("jvm", true));

        assertThat(detail.confirmed()).isTrue();
        assertThat(detail.analysis()).isNotNull();
        assertThat(detail.analysis().topics()).extracting(ReviewTagResponse::tag).contains("jvm");
        verify(actionService).syncFromDrafts(eq(30L), eq(20L), eq(7L), anyList());
    }

    @Test
    void statusAndDeleteRequireExistingSession() {
        assertCode(() -> service().status(30L), ErrorCode.NOT_FOUND);
        assertCode(() -> service().delete(30L), ErrorCode.NOT_FOUND);

        when(sessionMapper.selectById(30L)).thenReturn(session(30L, 20L));
        when(sessionMapper.deleteById(30L)).thenReturn(1);

        var status = service().status(30L);
        assertThat(status.status()).isEqualTo("READY");
        service().delete(30L);
        verify(sessionMapper).deleteById(30L);
    }

    @Test
    void listByApplicationMapsNames() {
        when(eventMapper.selectList(any())).thenReturn(List.of(event(20L, 7L)));
        when(sessionMapper.selectList(any())).thenReturn(List.of(session(30L, 20L)));
        stubLookups();

        var items = service().listByApplication(7L);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).companyName()).isEqualTo("字节跳动");
        assertThat(items.get(0).roundNo()).isEqualTo(1);
    }

    @Test
    void pageKeywordReturnsSnippetWithNotesFallback() {
        when(companyMapper.selectList(any())).thenReturn(List.of());
        when(jobMapper.selectList(any())).thenReturn(List.of());
        ReviewSession transcriptHit = session(30L, 20L);
        ReviewSession notesOnly = session(31L, 20L);
        notesOnly.setTranscript(null);
        notesOnly.setNotes("复盘笔记：JVM 分区还需补");
        Page<ReviewSession> page = new Page<>(1, 20);
        page.setRecords(List.of(transcriptHit, notesOnly));
        page.setTotal(2);
        when(sessionMapper.selectPage(any(), any())).thenReturn(page);
        stubLookups();

        var result = service().page(1, 20, null, null, "jvm");

        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).matchSnippet()).contains("JVM 内存模型");
        assertThat(result.items().get(1).matchSnippet()).contains("JVM 分区");
    }

    private static ReviewAnalysis analysisRow() {
        ReviewAnalysis row = new ReviewAnalysis();
        row.setId(40L);
        row.setReviewSessionId(30L);
        row.setPromptVersion(ReviewPromptFactory.VERSION);
        row.setRubricVersion("interview-rubric-v1");
        row.setTaxonomyVersion("taxonomy-v1");
        row.setLlmProvider("deepseek");
        row.setLlmModel("deepseek-flash");
        row.setDegraded(false);
        return row;
    }

    private static ReviewAnalysisUpdateRequest updateRequest(String topic, boolean confirmed) {
        return new ReviewAnalysisUpdateRequest(
                List.of(new ReviewQuestionResponse(1, "问题", "回答", 80, List.of(), List.of(topic),
                        List.of(), "证据", "点评", "参考")),
                List.of(new ReviewTagResponse(topic, null, 0.8, "证据")),
                List.of(new ReviewTagResponse("shallow-depth", null, 0.6, "证据")),
                new ReviewSummaryResponse("整体", List.of("优势"), List.of("改进"), List.of("准备")),
                List.of(new ImprovementActionDraftResponse("复习 GC", "知识点补强", "理由")),
                confirmed);
    }

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo(expected));
    }
}
