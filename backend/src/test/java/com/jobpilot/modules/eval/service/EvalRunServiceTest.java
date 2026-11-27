package com.jobpilot.modules.eval.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.modules.eval.core.EvalSceneCatalog;
import com.jobpilot.modules.eval.core.EvalSceneCatalog.EvalScene;
import com.jobpilot.modules.eval.dto.CreateEvalRunRequest;
import com.jobpilot.modules.eval.dto.EvalRunDetail;
import com.jobpilot.modules.eval.dto.EvalSceneModel;
import com.jobpilot.modules.eval.entity.LlmEvalRun;
import com.jobpilot.modules.eval.mapper.LlmEvalRunCaseMapper;
import com.jobpilot.modules.eval.mapper.LlmEvalRunMapper;
import com.jobpilot.modules.llm.dto.LlmScene;
import com.jobpilot.modules.llm.service.LlmSceneService;
import com.jobpilot.modules.llm.service.LlmSceneService.SceneModel;
import com.jobpilot.modules.prompt.catalog.PromptVersionSource;
import com.jobpilot.modules.prompt.service.PromptRegistry;
import com.jobpilot.modules.prompt.service.PromptTemplate;
import com.jobpilot.modules.resume.ai.AiResumeAnalysis;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 创建校验、快照与调度、取消边界。 */
@ExtendWith(MockitoExtension.class)
class EvalRunServiceTest {

    @Mock
    private LlmEvalRunMapper runMapper;
    @Mock
    private LlmEvalRunCaseMapper caseMapper;
    @Mock
    private EvalSceneCatalog catalog;
    @Mock
    private LlmSceneService sceneService;
    @Mock
    private PromptRegistry registry;
    @Mock
    private EvalRunner runner;
    @Mock
    private ThreadPoolTaskExecutor executor;

    @BeforeAll
    static void initLambdaCache() {
        com.jobpilot.LambdaCacheSupport.init(LlmEvalRun.class,
                com.jobpilot.modules.eval.entity.LlmEvalRunCase.class);
    }

    private EvalRunService service() {
        return new EvalRunService(runMapper, caseMapper, catalog, sceneService, registry, runner, executor);
    }

    private static EvalScene resumeScene() {
        return new EvalScene("resume-analysis", "简历分析", "resume-analysis",
                LlmScene.RESUME_ANALYZE, false, AiResumeAnalysis.class);
    }

    @Test
    void createRejectsUnsupportedScene() {
        when(catalog.get("unknown"))
                .thenThrow(BizException.of(ErrorCode.BAD_REQUEST, "不支持的评测场景: unknown"));
        assertThatThrownBy(() -> service().create(new CreateEvalRunRequest(List.of("unknown"), 1)))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).errorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void createRejectsConcurrentRun() {
        when(catalog.get("resume-analysis")).thenReturn(resumeScene());
        when(runMapper.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> service().create(new CreateEvalRunRequest(List.of("resume-analysis"), 1)))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).errorCode()).isEqualTo(ErrorCode.EVAL_RUN_IN_PROGRESS);
    }

    @Test
    void cancelFinishedRunIsRejected() {
        LlmEvalRun run = new LlmEvalRun();
        run.setId(5L);
        run.setStatus("COMPLETED");
        when(runMapper.selectById(5L)).thenReturn(run);
        assertThatThrownBy(() -> service().cancel(5L))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).errorCode()).isEqualTo(ErrorCode.EVAL_RUN_FINISHED);
    }

    @Test
    void createSnapshotsAndDispatchesWorker() {
        AtomicReference<LlmEvalRun> saved = new AtomicReference<>();
        when(catalog.get("resume-analysis")).thenReturn(resumeScene());
        when(runMapper.selectCount(any())).thenReturn(0L);
        when(sceneService.resolve(LlmScene.RESUME_ANALYZE)).thenReturn(new SceneModel("p", "m"));
        when(registry.template("resume-analysis")).thenReturn(new PromptTemplate("resume-analysis",
                "system", "{{resumeText}}", "resume-analysis-v3", "hash", PromptVersionSource.BUILTIN));
        when(catalog.cases(List.of("resume-analysis"))).thenReturn(List.of());
        when(catalog.combinedCorpusHash(List.of("resume-analysis"))).thenReturn("corpus-hash");
        doAnswer(invocation -> {
            LlmEvalRun run = invocation.getArgument(0);
            run.setId(9L);
            saved.set(run);
            return 1;
        }).when(runMapper).insert(any(LlmEvalRun.class));
        when(runMapper.selectById(9L)).thenAnswer(invocation -> saved.get());
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(executor).execute(any(Runnable.class));

        EvalRunDetail detail = service().create(new CreateEvalRunRequest(List.of("resume-analysis"), 2));

        assertThat(detail.id()).isEqualTo(9L);
        assertThat(detail.status()).isEqualTo("PENDING");
        assertThat(detail.totalCases()).isZero();
        assertThat(detail.models()).containsExactly(new EvalSceneModel("resume-analysis", "p", "m"));
        assertThat(detail.prompts().get(0).promptVersion()).isEqualTo("resume-analysis-v3");
        assertThat(saved.get().getParams()).contains("\"repeat\":2");
        verify(runner).run(9L);
    }
}
