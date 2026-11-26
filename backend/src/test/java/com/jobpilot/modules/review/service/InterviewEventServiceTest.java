package com.jobpilot.modules.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.jobpilot.LambdaCacheSupport;
import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.modules.application.entity.JobApplication;
import com.jobpilot.modules.application.mapper.JobApplicationMapper;
import com.jobpilot.modules.company.entity.Company;
import com.jobpilot.modules.company.mapper.CompanyMapper;
import com.jobpilot.modules.job.entity.JobPosition;
import com.jobpilot.modules.job.mapper.JobPositionMapper;
import com.jobpilot.modules.review.dto.CreateInterviewEventRequest;
import com.jobpilot.modules.review.dto.UpdateInterviewEventRequest;
import com.jobpilot.modules.review.entity.InterviewEvent;
import com.jobpilot.modules.review.entity.ReviewSession;
import com.jobpilot.modules.review.mapper.InterviewEventMapper;
import com.jobpilot.modules.review.mapper.ReviewSessionMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 真实面试轮次：投递定位、轮次唯一、字段修正、删除守卫与列表映射。 */
@ExtendWith(MockitoExtension.class)
class InterviewEventServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private InterviewEventMapper eventMapper;
    @Mock
    private ReviewSessionMapper reviewSessionMapper;
    @Mock
    private JobApplicationMapper applicationMapper;
    @Mock
    private JobPositionMapper jobMapper;
    @Mock
    private CompanyMapper companyMapper;

    @BeforeAll
    static void initLambdaCache() {
        LambdaCacheSupport.init(InterviewEvent.class, ReviewSession.class, JobApplication.class,
                JobPosition.class, Company.class);
    }

    private InterviewEventService service() {
        return new InterviewEventService(eventMapper, reviewSessionMapper, applicationMapper, jobMapper,
                companyMapper);
    }

    private static JobApplication application(long id, long jobId) {
        JobApplication application = new JobApplication();
        application.setId(id);
        application.setJobPositionId(jobId);
        application.setStatus("INTERVIEWING");
        return application;
    }

    private static JobPosition job(String status) {
        JobPosition job = new JobPosition();
        job.setId(1L);
        job.setCompanyId(2L);
        job.setTitle("Java 后端");
        job.setStatus(status);
        return job;
    }

    private static Company company() {
        Company company = new Company();
        company.setId(2L);
        company.setName("字节跳动");
        return company;
    }

    private static InterviewEvent event(long id, long applicationId, int roundNo) {
        InterviewEvent event = new InterviewEvent();
        event.setId(id);
        event.setApplicationId(applicationId);
        event.setRoundNo(roundNo);
        event.setRoundType("TECH");
        event.setInterviewTime(OffsetDateTime.now());
        event.setResult("PENDING");
        event.setCreatedAt(OffsetDateTime.now());
        event.setUpdatedAt(OffsetDateTime.now());
        return event;
    }

    private void stubLookups() {
        when(applicationMapper.selectByIds(anyList())).thenReturn(List.of(application(7L, 1L)));
        when(jobMapper.selectByIds(anyList())).thenReturn(List.of(job("INTERVIEWING")));
        when(companyMapper.selectByIds(anyList())).thenReturn(List.of(company()));
    }

    @Test
    void createWithApplicationPersistsDefaults() {
        when(applicationMapper.selectById(7L)).thenReturn(application(7L, 1L));
        when(eventMapper.selectCount(any())).thenReturn(0L);
        when(eventMapper.insert(any(InterviewEvent.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, InterviewEvent.class).setId(9L);
            return 1;
        });
        stubLookups();

        var response = service().create(new CreateInterviewEventRequest(7L, null, 1, "TECH", " 技术负责人 ",
                "VIDEO", null, null, " 一面 "));

        assertThat(response.id()).isEqualTo(9L);
        assertThat(response.applicationId()).isEqualTo(7L);
        assertThat(response.companyName()).isEqualTo("字节跳动");
        assertThat(response.jobTitle()).isEqualTo("Java 后端");
        assertThat(response.interviewerRole()).isEqualTo("技术负责人");
        assertThat(response.result()).isEqualTo("PENDING");
        assertThat(response.notes()).isEqualTo("一面");
        assertThat(response.reviewSessionId()).isNull();
    }

    @Test
    void createWithJobReusesLatestApplication() {
        when(jobMapper.selectById(1L)).thenReturn(job("SAVED"));
        when(applicationMapper.selectList(any())).thenReturn(List.of(application(7L, 1L)));
        when(eventMapper.selectCount(any())).thenReturn(0L);
        when(eventMapper.insert(any(InterviewEvent.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, InterviewEvent.class).setId(9L);
            return 1;
        });
        stubLookups();

        var response = service().create(new CreateInterviewEventRequest(null, 1L, 2, "HR", null, null, null,
                "PASS", null));

        assertThat(response.applicationId()).isEqualTo(7L);
        assertThat(response.result()).isEqualTo("PASS");
        verify(applicationMapper, never()).insert(any(JobApplication.class));
        verify(jobMapper, never()).update(any(), any());
    }

    @Test
    void createWithJobCreatesApplicationAndMovesJobForward() {
        when(jobMapper.selectById(1L)).thenReturn(job("SAVED"));
        when(applicationMapper.selectList(any())).thenReturn(List.of());
        when(applicationMapper.insert(any(JobApplication.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, JobApplication.class).setId(7L);
            return 1;
        });
        when(eventMapper.selectCount(any())).thenReturn(0L);
        when(eventMapper.insert(any(InterviewEvent.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, InterviewEvent.class).setId(9L);
            return 1;
        });
        when(jobMapper.update(any(), any())).thenReturn(1);
        stubLookups();

        var response = service().create(new CreateInterviewEventRequest(null, 1L, 1, "TECH", null, null, null,
                null, null));

        assertThat(response.applicationId()).isEqualTo(7L);
        verify(applicationMapper).insert(any(JobApplication.class));
        verify(jobMapper).update(any(), any());
    }

    @Test
    void createRejectsDuplicateRoundAndInvalidEnvelope() {
        when(applicationMapper.selectById(7L)).thenReturn(application(7L, 1L));
        when(eventMapper.selectCount(any())).thenReturn(1L);
        assertCode(() -> service().create(new CreateInterviewEventRequest(7L, null, 1, "TECH", null, null, null,
                null, null)), ErrorCode.REVIEW_ROUND_EXISTS);

        assertCode(() -> service().create(new CreateInterviewEventRequest(null, null, 1, "TECH", null, null, null,
                null, null)), ErrorCode.BAD_REQUEST);
        assertCode(() -> service().create(new CreateInterviewEventRequest(7L, null, 1, "UNKNOWN", null, null, null,
                null, null)), ErrorCode.BAD_REQUEST);
        assertCode(() -> service().create(new CreateInterviewEventRequest(7L, null, 1, "TECH", null, "ONSITE_X",
                null, null, null)), ErrorCode.BAD_REQUEST);
        assertCode(() -> service().create(new CreateInterviewEventRequest(7L, null, 1, "TECH", null, null, null,
                "MAYBE", null)), ErrorCode.BAD_REQUEST);
    }

    @Test
    void quickCreateBuildsCompanyManualJobAndRound() {
        when(companyMapper.selectOne(any())).thenReturn(null);
        when(companyMapper.insert(any(Company.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Company.class).setId(2L);
            return 1;
        });
        when(jobMapper.selectOne(any())).thenReturn(null);
        when(jobMapper.insert(any(JobPosition.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, JobPosition.class).setId(1L);
            return 1;
        });
        when(jobMapper.selectById(1L)).thenReturn(job("INTERVIEWING"));
        when(applicationMapper.selectList(any())).thenReturn(List.of());
        when(applicationMapper.insert(any(JobApplication.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, JobApplication.class).setId(7L);
            return 1;
        });
        when(eventMapper.selectOne(any())).thenReturn(null);
        when(eventMapper.insert(any(InterviewEvent.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, InterviewEvent.class).setId(9L);
            return 1;
        });
        var response = service().ensureEventForManualJob(null, " 字节跳动 ", " 公司介绍 ", " Java 后端 ",
                " JD 原文 ", 1, "TECH", null, null, null);

        assertThat(response.getId()).isEqualTo(9L);
        ArgumentCaptor<Company> companyCaptor = ArgumentCaptor.forClass(Company.class);
        verify(companyMapper).insert(companyCaptor.capture());
        assertThat(companyCaptor.getValue().getName()).isEqualTo("字节跳动");
        assertThat(companyCaptor.getValue().getIntro()).isEqualTo("公司介绍");
        ArgumentCaptor<JobPosition> jobCaptor = ArgumentCaptor.forClass(JobPosition.class);
        verify(jobMapper).insert(jobCaptor.capture());
        JobPosition created = jobCaptor.getValue();
        assertThat(created.getCompanyId()).isEqualTo(2L);
        assertThat(created.getTitle()).isEqualTo("Java 后端");
        assertThat(created.getRawText()).isEqualTo("JD 原文");
        assertThat(created.getSourceType()).isEqualTo("MANUAL");
        assertThat(created.getParseStatus()).isEqualTo("COMPLETED");
        assertThat(created.getStatus()).isEqualTo("INTERVIEWING");
        verify(applicationMapper).insert(any(JobApplication.class));
    }

    @Test
    void quickCreateReusesExistingCompanyAndJob() {
        when(companyMapper.selectById(2L)).thenReturn(company());
        when(jobMapper.selectOne(any())).thenReturn(job("INTERVIEWING"));
        when(jobMapper.selectById(1L)).thenReturn(job("INTERVIEWING"));
        when(applicationMapper.selectList(any())).thenReturn(List.of(application(7L, 1L)));
        when(eventMapper.selectOne(any())).thenReturn(null);
        when(eventMapper.insert(any(InterviewEvent.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, InterviewEvent.class).setId(9L);
            return 1;
        });
        var response = service().ensureEventForManualJob(2L, null, null, "Java 后端", null, 1, "TECH",
                null, null, null);

        assertThat(response.getId()).isEqualTo(9L);
        verify(companyMapper, never()).insert(any(Company.class));
        verify(jobMapper, never()).insert(any(JobPosition.class));
        verify(applicationMapper, never()).insert(any(JobApplication.class));
    }

    @Test
    void quickCreateRejectsInvalidEnvelope() {
        assertCode(() -> service().ensureEventForManualJob(null, null, null, "Java 后端", null, 1, "TECH",
                null, null, null), ErrorCode.BAD_REQUEST);
        assertCode(() -> service().ensureEventForManualJob(1L, "字节跳动", null, "Java 后端", null, 1, "TECH",
                null, null, null), ErrorCode.BAD_REQUEST);
        assertCode(() -> service().ensureEventForManualJob(null, "字节跳动", null, "  ", null, 1, "TECH",
                null, null, null), ErrorCode.BAD_REQUEST);
        assertCode(() -> service().ensureEventForManualJob(null, "字节跳动", null, "Java 后端", null, 0, "TECH",
                null, null, null), ErrorCode.BAD_REQUEST);
        assertCode(() -> service().ensureEventForManualJob(null, "字节跳动", null, "Java 后端", null, 1, "UNKNOWN",
                null, null, null), ErrorCode.BAD_REQUEST);
    }

    @Test
    void updateAppliesPartialFieldsAndValidates() {
        when(eventMapper.selectById(9L)).thenReturn(event(9L, 7L, 1));
        stubLookups();

        var response = service().update(9L, new UpdateInterviewEventRequest(
                MAPPER.getNodeFactory().textNode("HR"), NullNode.getInstance(),
                MAPPER.getNodeFactory().textNode("ONSITE"),
                MAPPER.getNodeFactory().textNode("2026-11-20T10:00:00+08:00"),
                MAPPER.getNodeFactory().textNode("PASS"), NullNode.getInstance()));

        assertThat(response.roundType()).isEqualTo("HR");
        assertThat(response.format()).isEqualTo("ONSITE");
        assertThat(response.interviewerRole()).isNull();
        assertThat(response.result()).isEqualTo("PASS");
        assertThat(response.notes()).isNull();

        assertCode(() -> service().update(9L, new UpdateInterviewEventRequest(
                MAPPER.getNodeFactory().textNode("INVALID"), null, null, null, null, null)),
                ErrorCode.BAD_REQUEST);
        assertCode(() -> service().update(9L, new UpdateInterviewEventRequest(
                null, null, null, NullNode.getInstance(), null, null)), ErrorCode.BAD_REQUEST);
        assertCode(() -> service().update(9L, new UpdateInterviewEventRequest(
                null, null, null, null, NullNode.getInstance(), null)), ErrorCode.BAD_REQUEST);
    }

    @Test
    void deleteGuardsReviewAndDeletes() {
        when(eventMapper.selectById(9L)).thenReturn(event(9L, 7L, 1));
        when(reviewSessionMapper.selectCount(any())).thenReturn(1L);
        assertCode(() -> service().delete(9L), ErrorCode.RESOURCE_REFERENCED);
        verify(eventMapper, never()).deleteById(9L);

        when(reviewSessionMapper.selectCount(any())).thenReturn(0L);
        service().delete(9L);
        verify(eventMapper).deleteById(9L);
    }

    @Test
    void pageMapsNamesAndReviewStatus() {
        Page<InterviewEvent> page = new Page<>(1, 20);
        page.setRecords(List.of(event(9L, 7L, 2)));
        page.setTotal(1);
        when(eventMapper.selectPage(any(), any())).thenReturn(page);
        stubLookups();
        ReviewSession review = new ReviewSession();
        review.setId(11L);
        review.setEventId(9L);
        review.setStatus("READY");
        when(reviewSessionMapper.selectList(any())).thenReturn(List.of(review));

        var result = service().page(1, 20, 7L, "PENDING", "TECH");

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).reviewSessionId()).isEqualTo(11L);
        assertThat(result.items().get(0).reviewStatus()).isEqualTo("READY");
        assertThat(result.items().get(0).companyName()).isEqualTo("字节跳动");
    }

    @Test
    void statsAndExistsAggregateByApplication() {
        when(eventMapper.selectList(any())).thenReturn(List.of(event(9L, 7L, 1), event(10L, 7L, 3)));

        var stats = service().stats(List.of(7L));

        assertThat(stats.get(7L).count()).isEqualTo(2);
        assertThat(stats.get(7L).latestRoundNo()).isEqualTo(3);
        assertThat(service().stats(List.of()).isEmpty()).isTrue();

        when(eventMapper.selectCount(any())).thenReturn(2L);
        assertThat(service().existsByApplication(7L)).isTrue();
    }

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo(expected));
    }
}
