package com.jobpilot.modules.job.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jobpilot.LambdaCacheSupport;
import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.common.llm.ChatResult;
import com.jobpilot.common.llm.LlmClient;
import com.jobpilot.infrastructure.llm.LlmCallLogger;
import com.jobpilot.infrastructure.llm.ProviderClientRegistry;
import com.jobpilot.modules.company.entity.Company;
import com.jobpilot.modules.company.mapper.CompanyMapper;
import com.jobpilot.modules.job.dto.CreateJobMatchRequest;
import com.jobpilot.modules.job.entity.JobMatchResult;
import com.jobpilot.modules.job.entity.JobPosition;
import com.jobpilot.modules.job.mapper.JobMatchResultMapper;
import com.jobpilot.modules.job.mapper.JobPositionMapper;
import com.jobpilot.modules.job.prompt.JobMatchPromptFactory;
import com.jobpilot.modules.llm.dto.LlmScene;
import com.jobpilot.modules.llm.service.LlmSceneService;
import com.jobpilot.modules.resume.entity.Resume;
import com.jobpilot.modules.resume.mapper.ResumeMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 匹配评分：唯一组合重跑、执行中 409、本地加权总分、状态查询。 */
@ExtendWith(MockitoExtension.class)
class JobMatchServiceTest {

    @Mock
    private JobMatchResultMapper matchMapper;
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
    private ThreadPoolTaskExecutor executor;

    @BeforeAll
    static void initLambdaCache() {
        LambdaCacheSupport.init(JobMatchResult.class, JobPosition.class, Company.class, Resume.class);
    }

    private JobMatchService service() {
        return new JobMatchService(matchMapper, jobMapper, companyMapper, resumeMapper, sceneService,
                registry, callLogger, new JobMatchPromptFactory(), executor);
    }

    private static Resume resume() {
        Resume resume = new Resume();
        resume.setId(1L);
        resume.setResumeText("3 年 Java 后端");
        return resume;
    }

    private static JobPosition job() {
        JobPosition job = new JobPosition();
        job.setId(2L);
        job.setTitle("Java 后端");
        job.setRawText("熟悉 Java 与 Redis");
        job.setParseStatus("COMPLETED");
        return job;
    }

    @Test
    void createRejectsMissingResourcesAndRunningTask() {
        when(resumeMapper.selectById(1L)).thenReturn(null);
        assertCode(() -> service().create(new CreateJobMatchRequest(1L, 2L)), ErrorCode.NOT_FOUND);

        when(resumeMapper.selectById(1L)).thenReturn(resume());
        when(jobMapper.selectById(2L)).thenReturn(null);
        assertCode(() -> service().create(new CreateJobMatchRequest(1L, 2L)), ErrorCode.NOT_FOUND);

        when(jobMapper.selectById(2L)).thenReturn(job());
        JobMatchResult running = new JobMatchResult();
        running.setId(3L);
        running.setStatus("PROCESSING");
        when(matchMapper.selectOne(any())).thenReturn(running);
        assertCode(() -> service().create(new CreateJobMatchRequest(1L, 2L)), ErrorCode.TASK_IN_PROGRESS);
    }

    @Test
    void createRunsAsyncAndComputesWeightedScoreLocally() {
        when(resumeMapper.selectById(1L)).thenReturn(resume());
        when(jobMapper.selectById(2L)).thenReturn(job());
        when(matchMapper.selectOne(any())).thenReturn(null);
        JobMatchResult entity = new JobMatchResult();
        when(matchMapper.insert(any(JobMatchResult.class))).thenAnswer(invocation -> {
            JobMatchResult saved = invocation.getArgument(0);
            saved.setId(3L);
            return 1;
        });
        when(matchMapper.selectById(3L)).thenAnswer(invocation -> entity);
        entity.setId(3L);
        entity.setResumeId(1L);
        entity.setJobPositionId(2L);
        entity.setStatus("PROCESSING");
        when(sceneService.resolve(LlmScene.JOB_MATCH))
                .thenReturn(new LlmSceneService.SceneModel("p", "m"));
        LlmClient client = org.mockito.Mockito.mock(LlmClient.class);
        when(registry.get("p")).thenReturn(client);
        when(client.chatJson(any(), anyList())).thenReturn(new ChatResult("""
                {"skillMatch":80,"experienceMatch":70,"bonusCoverage":60,
                 "gaps":["缺少 Kafka 实战"],"preparationAdvice":["补充消息队列项目"]}
                """, "m", 10, 20, 30));
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(executor).execute(any(Runnable.class));

        var response = service().create(new CreateJobMatchRequest(1L, 2L));

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.overallScore()).isEqualTo(73);
        assertThat(response.detail().dimensions()).extracting("weight").containsExactly(50, 30, 20);
        assertThat(response.detail().gaps()).contains("缺少 Kafka 实战");
        verify(matchMapper).insert(any(JobMatchResult.class));
        verify(matchMapper).updateById(entity);
    }

    @Test
    void createRollsBackToPendingWhenQueueRejects() {
        when(resumeMapper.selectById(1L)).thenReturn(resume());
        when(jobMapper.selectById(2L)).thenReturn(job());
        when(matchMapper.selectOne(any())).thenReturn(null);
        when(matchMapper.insert(any(JobMatchResult.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, JobMatchResult.class).setId(3L);
            return 1;
        });
        org.mockito.Mockito.doThrow(new IllegalStateException("full"))
                .when(executor).execute(any(Runnable.class));

        assertCode(() -> service().create(new CreateJobMatchRequest(1L, 2L)), ErrorCode.INTERNAL_ERROR);
        verify(matchMapper).update(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void pageAndStatusExposeDetailOnlyWhenCompleted() {
        JobMatchResult completed = new JobMatchResult();
        completed.setId(3L);
        completed.setResumeId(1L);
        completed.setJobPositionId(2L);
        completed.setStatus("COMPLETED");
        completed.setOverallScore(73);
        completed.setDetail("""
                {"dimensions":[{"key":"skillMatch","label":"技能匹配","score":80,"weight":50},
                {"key":"experienceMatch","label":"经验匹配","score":70,"weight":30},
                {"key":"bonusCoverage","label":"加分项覆盖","score":60,"weight":20}],
                 "gaps":["缺少 Kafka"],"preparationAdvice":["补项目"]}
                """);
        when(matchMapper.selectById(3L)).thenReturn(completed);
        when(jobMapper.selectById(2L)).thenReturn(job());

        var status = service().status(3L);
        assertThat(status.detail().dimensions()).hasSize(3);
        assertThat(status.companyName()).isNull();

        Page<JobMatchResult> page = new Page<>(1, 20);
        page.setRecords(List.of(completed));
        page.setTotal(1);
        when(matchMapper.selectPage(any(), any())).thenReturn(page);
        when(jobMapper.selectByIds(anyList())).thenReturn(List.of(job()));
        var list = service().page(1, 20, 1L, null);
        assertThat(list.items()).singleElement().satisfies(item ->
                assertThat(item.jobPositionTitle()).isEqualTo("Java 后端"));

        when(matchMapper.selectById(9L)).thenReturn(null);
        assertCode(() -> service().status(9L), ErrorCode.NOT_FOUND);
    }

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo(expected));
    }
}
