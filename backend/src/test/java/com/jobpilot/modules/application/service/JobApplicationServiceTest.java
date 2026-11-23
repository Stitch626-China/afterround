package com.jobpilot.modules.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.jobpilot.LambdaCacheSupport;
import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.modules.application.dto.CreateJobApplicationRequest;
import com.jobpilot.modules.application.dto.UpdateJobApplicationRequest;
import com.jobpilot.modules.application.entity.JobApplication;
import com.jobpilot.modules.application.mapper.JobApplicationMapper;
import com.jobpilot.modules.company.entity.Company;
import com.jobpilot.modules.company.mapper.CompanyMapper;
import com.jobpilot.modules.job.entity.JobPosition;
import com.jobpilot.modules.job.mapper.JobPositionMapper;
import com.jobpilot.modules.offer.service.OfferService;
import com.jobpilot.modules.resume.entity.Resume;
import com.jobpilot.modules.resume.mapper.ResumeMapper;
import com.jobpilot.modules.review.dto.InterviewEventListItemResponse;
import com.jobpilot.modules.review.service.InterviewEventService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 投递档案：创建、状态向前同步、字段修正、删除与关键字分页。 */
@ExtendWith(MockitoExtension.class)
class JobApplicationServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private JobApplicationMapper applicationMapper;
    @Mock
    private JobPositionMapper jobMapper;
    @Mock
    private CompanyMapper companyMapper;
    @Mock
    private ResumeMapper resumeMapper;
    @Mock
    private OfferService offerService;
    @Mock
    private InterviewEventService eventService;

    @BeforeAll
    static void initLambdaCache() {
        LambdaCacheSupport.init(JobApplication.class, JobPosition.class, Company.class, Resume.class);
    }

    private JobApplicationService service() {
        return new JobApplicationService(applicationMapper, jobMapper, companyMapper, resumeMapper, offerService,
                eventService);
    }

    private static JobPosition job(String status) {
        JobPosition job = new JobPosition();
        job.setId(1L);
        job.setCompanyId(2L);
        job.setTitle("Java 后端");
        job.setStatus(status);
        return job;
    }

    private static JobApplication application(String status) {
        JobApplication application = new JobApplication();
        application.setId(5L);
        application.setJobPositionId(1L);
        application.setStatus(status);
        return application;
    }

    private static InterviewEventListItemResponse event() {
        return new InterviewEventListItemResponse(9L, 5L, 2L, "字节跳动", 1L, "Java 后端", 1, "TECH",
                "技术负责人", "VIDEO", java.time.OffsetDateTime.now(), "PENDING", null, null, null, null, null);
    }

    @Test
    void createRecordsApplicationAndSyncsJobStatus() {
        when(jobMapper.selectById(1L)).thenReturn(job("SAVED"));
        when(applicationMapper.insert(any(JobApplication.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, JobApplication.class).setId(5L);
            return 1;
        });
        when(jobMapper.selectByIds(anyList())).thenReturn(List.of(job("APPLIED")));
        Company company = new Company();
        company.setId(2L);
        company.setName("字节跳动");
        when(companyMapper.selectByIds(anyList())).thenReturn(List.of(company));
        when(jobMapper.update(any(), any())).thenReturn(1);

        var response = service().create(new CreateJobApplicationRequest(1L, null, "BOSS", null, null));

        assertThat(response.status()).isEqualTo("APPLIED");
        assertThat(response.jobTitle()).isEqualTo("Java 后端");
        assertThat(response.companyName()).isEqualTo("字节跳动");
        verify(jobMapper).update(any(), any());
    }

    @Test
    void createRejectsMissingJobOrResume() {
        assertCode(() -> service().create(new CreateJobApplicationRequest(1L, null, null, null, null)),
                ErrorCode.NOT_FOUND);

        when(jobMapper.selectById(1L)).thenReturn(job("SAVED"));
        assertCode(() -> service().create(new CreateJobApplicationRequest(1L, 9L, null, null, null)),
                ErrorCode.NOT_FOUND);
    }

    @Test
    void updateOnlyMovesJobStatusForward() {
        when(applicationMapper.selectById(5L)).thenReturn(application("APPLIED"));
        when(jobMapper.selectById(1L)).thenReturn(job("APPLIED"));
        when(jobMapper.selectByIds(anyList())).thenReturn(List.of(job("APPLIED")));
        when(companyMapper.selectByIds(anyList())).thenReturn(List.of());
        when(jobMapper.update(any(), any())).thenReturn(1);

        var withdrawn = service().update(5L, new UpdateJobApplicationRequest(
                null, null, null, MAPPER.getNodeFactory().textNode("WITHDRAWN"), null, null));
        assertThat(withdrawn.status()).isEqualTo("WITHDRAWN");
        verify(jobMapper, never()).update(any(), any());

        var offered = service().update(5L, new UpdateJobApplicationRequest(
                null, null, null, MAPPER.getNodeFactory().textNode("OFFERED"), null, null));
        assertThat(offered.status()).isEqualTo("OFFERED");
        verify(jobMapper).update(any(), any());
    }

    @Test
    void updateRejectsInvalidStatusAndClearsOptionalFields() {
        when(applicationMapper.selectById(5L)).thenReturn(application("APPLIED"));
        when(jobMapper.selectById(1L)).thenReturn(job("APPLIED"));
        assertCode(() -> service().update(5L, new UpdateJobApplicationRequest(
                null, null, null, MAPPER.getNodeFactory().textNode("UNKNOWN"), null, null)),
                ErrorCode.BAD_REQUEST);

        when(jobMapper.selectByIds(anyList())).thenReturn(List.of(job("APPLIED")));
        when(companyMapper.selectByIds(anyList())).thenReturn(List.of());
        var response = service().update(5L, new UpdateJobApplicationRequest(
                NullNode.getInstance(), MAPPER.getNodeFactory().textNode(" 内推 "), null, null,
                NullNode.getInstance(), MAPPER.getNodeFactory().textNode("   ")));
        assertThat(response.resumeId()).isNull();
        assertThat(response.channel()).isEqualTo("内推");
        assertThat(response.outcome()).isNull();
        assertThat(response.notes()).isNull();
    }

    @Test
    void deleteAndTimelineRequireExistingApplication() {
        assertCode(() -> service().delete(5L), ErrorCode.NOT_FOUND);
        assertCode(() -> service().timeline(5L), ErrorCode.NOT_FOUND);

        JobApplication application = application("APPLIED");
        when(applicationMapper.selectById(5L)).thenReturn(application);
        when(jobMapper.selectByIds(anyList())).thenReturn(List.of(job("APPLIED")));
        when(companyMapper.selectByIds(anyList())).thenReturn(List.of());
        when(eventService.listByApplication(5L)).thenReturn(List.of(event()));

        var timeline = service().timeline(5L);
        assertThat(timeline.application().id()).isEqualTo(5L);
        assertThat(timeline.events()).hasSize(1);
        assertThat(timeline.events().get(0).roundNo()).isEqualTo(1);
        assertThat(timeline.reviews()).isEmpty();
        assertThat(timeline.actions()).isEmpty();
        assertThat(timeline.offer()).isNull();

        service().delete(5L);
        verify(applicationMapper).deleteById(5L);
    }

    @Test
    void deleteRejectedWhenInterviewEventsExist() {
        when(applicationMapper.selectById(5L)).thenReturn(application("INTERVIEWING"));
        when(eventService.existsByApplication(5L)).thenReturn(true);

        assertCode(() -> service().delete(5L), ErrorCode.RESOURCE_REFERENCED);
        verify(applicationMapper, never()).deleteById(5L);
    }

    @Test
    void pageReturnsEmptyWhenKeywordMissesAndMapsNamesOtherwise() {
        when(companyMapper.selectList(any())).thenReturn(List.of());
        when(jobMapper.selectList(any())).thenReturn(List.of());
        var empty = service().page(1, 20, null, null, null, "不存在");
        assertThat(empty.items()).isEmpty();
        verify(applicationMapper, never()).selectPage(any(), any());

        Page<JobApplication> page = new Page<>(1, 20);
        page.setRecords(List.of(application("APPLIED")));
        page.setTotal(1);
        when(jobMapper.selectList(any())).thenReturn(List.of(job("APPLIED")));
        when(applicationMapper.selectPage(any(), any())).thenReturn(page);
        when(jobMapper.selectByIds(anyList())).thenReturn(List.of(job("APPLIED")));
        Company company = new Company();
        company.setId(2L);
        company.setName("字节跳动");
        when(companyMapper.selectByIds(anyList())).thenReturn(List.of(company));
        when(eventService.stats(anyList())).thenReturn(java.util.Map.of(5L,
                new InterviewEventService.InterviewStats(2, 3)));

        var result = service().page(1, 20, "APPLIED", 1L, null, "字节");

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).companyName()).isEqualTo("字节跳动");
        assertThat(result.items().get(0).interviewCount()).isEqualTo(2);
        assertThat(result.items().get(0).latestRoundNo()).isEqualTo(3);
    }

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo(expected));
    }
}
