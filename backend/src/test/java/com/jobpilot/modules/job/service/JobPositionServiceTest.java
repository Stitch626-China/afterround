package com.jobpilot.modules.job.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.jobpilot.LambdaCacheSupport;
import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.common.llm.ChatResult;
import com.jobpilot.common.llm.LlmClient;
import com.jobpilot.infrastructure.llm.LlmCallLogger;
import com.jobpilot.infrastructure.llm.ProviderClientRegistry;
import com.jobpilot.infrastructure.storage.FileStorage;
import com.jobpilot.modules.company.entity.Company;
import com.jobpilot.modules.company.mapper.CompanyMapper;
import com.jobpilot.modules.job.dto.BatchDeleteJobPositionsRequest;
import com.jobpilot.modules.job.dto.UpdateJobPositionRequest;
import com.jobpilot.modules.job.entity.JobPosition;
import com.jobpilot.modules.job.mapper.JobPositionMapper;
import com.jobpilot.modules.job.prompt.JobPositionPromptFactory;
import com.jobpilot.modules.llm.dto.LlmScene;
import com.jobpilot.modules.llm.service.LlmSceneService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.multipart.MultipartFile;

/** 岗位库：导入校验、解析状态机、公司自动关联、修正与删除。 */
@ExtendWith(MockitoExtension.class)
class JobPositionServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private JobPositionMapper jobMapper;
    @Mock
    private CompanyMapper companyMapper;
    @Mock
    private LlmSceneService sceneService;
    @Mock
    private ProviderClientRegistry registry;
    @Mock
    private LlmCallLogger callLogger;
    @Mock
    private FileStorage storage;
    @Mock
    private ThreadPoolTaskExecutor executor;

    @BeforeAll
    static void initLambdaCache() {
        LambdaCacheSupport.init(JobPosition.class, Company.class);
    }

    private JobPositionService service() {
        return new JobPositionService(jobMapper, companyMapper, sceneService, registry, callLogger,
                new JobPositionPromptFactory(), storage, executor);
    }

    private static JobPosition job(String parseStatus) {
        JobPosition job = new JobPosition();
        job.setId(1L);
        job.setSourceType("TEXT");
        job.setRawText("Java 后端，要求 Redis");
        job.setMustHaveSkills("[]");
        job.setNiceToHaveSkills("[]");
        job.setStatus("SAVED");
        job.setParseStatus(parseStatus);
        job.setRetryCount(0);
        return job;
    }

    @Test
    void importRejectsEmptyInputAndOversizeOrUnsupportedFile() throws Exception {
        assertCode(() -> service().importJd(null, " "), ErrorCode.BAD_REQUEST);

        MultipartFile unsupported = org.mockito.Mockito.mock(MultipartFile.class);
        when(unsupported.isEmpty()).thenReturn(false);
        when(unsupported.getOriginalFilename()).thenReturn("jd.xyz");
        assertCode(() -> service().importJd(unsupported, null), ErrorCode.UNSUPPORTED_MEDIA_TYPE);

        MultipartFile oversize = org.mockito.Mockito.mock(MultipartFile.class);
        when(oversize.isEmpty()).thenReturn(false);
        when(oversize.getSize()).thenReturn(6L * 1024 * 1024);
        assertCode(() -> service().importJd(oversize, null), ErrorCode.PAYLOAD_TOO_LARGE);
    }

    @Test
    void importTextStaysPendingWhenNoDefaultModel() {
        when(sceneService.resolveGlobal()).thenThrow(BizException.of(ErrorCode.BAD_REQUEST, "未配置"));
        when(jobMapper.insert(any(JobPosition.class))).thenAnswer(invocation -> {
            JobPosition entity = invocation.getArgument(0);
            entity.setId(1L);
            return 1;
        });
        when(jobMapper.selectById(1L)).thenAnswer(invocation -> job("PENDING"));

        var response = service().importJd(null, "Java 后端\n要求 Redis");

        assertThat(response.parseStatus()).isEqualTo("PENDING");
        assertThat(response.title()).isNull();
        verify(storage, never()).save(any(), any());
        verify(jobMapper).insert(any(JobPosition.class));
    }

    @Test
    void importFileParsesTextAndSavesOriginal() throws Exception {
        byte[] bytes = "Java 后端，要求 Redis".getBytes(StandardCharsets.UTF_8);
        MultipartFile file = org.mockito.Mockito.mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("jd.txt");
        when(file.getSize()).thenReturn((long) bytes.length);
        when(file.getBytes()).thenReturn(bytes);
        when(sceneService.resolveGlobal()).thenThrow(BizException.of(ErrorCode.BAD_REQUEST, "未配置"));
        when(jobMapper.insert(any(JobPosition.class))).thenAnswer(invocation -> {
            JobPosition entity = invocation.getArgument(0);
            entity.setId(2L);
            return 1;
        });
        when(jobMapper.selectById(2L)).thenReturn(job("PENDING"));

        var response = service().importJd(file, null);

        assertThat(response.parseStatus()).isEqualTo("PENDING");
        verify(storage).save(any(), any());
        verify(jobMapper).insert(any(JobPosition.class));
    }

    @Test
    void retryParsesAsynchronouslyAndAssociatesCompany() {
        JobPosition job = job("PROCESSING");
        when(jobMapper.selectById(1L)).thenReturn(job);
        when(jobMapper.update(any(), any())).thenReturn(1);
        when(sceneService.resolveGlobal()).thenReturn(new LlmSceneService.SceneModel("p", "m"));
        LlmClient client = org.mockito.Mockito.mock(LlmClient.class);
        when(registry.get("p")).thenReturn(client);
        when(client.chatJson(any(), anyList())).thenReturn(new ChatResult("""
                {"title":"Java 后端","companyName":"字节跳动","city":"上海","salaryMin":30000,
                 "salaryMax":20000,"experience":"3-5年","education":"本科",
                 "mustHaveSkills":["Java","Redis"],"niceToHaveSkills":["Kafka"],
                 "responsibility":"负责后端服务"}
                """, "m", 10, 20, 30));
        when(companyMapper.selectOne(any())).thenReturn(null);
        when(companyMapper.insert(any(Company.class))).thenAnswer(invocation -> {
            Company company = invocation.getArgument(0);
            company.setId(9L);
            return 1;
        });
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(executor).execute(any(Runnable.class));

        service().retryParse(1L);

        assertThat(job.getParseStatus()).isEqualTo("COMPLETED");
        assertThat(job.getTitle()).isEqualTo("Java 后端");
        assertThat(job.getSalaryMin()).isEqualTo(20000);
        assertThat(job.getSalaryMax()).isEqualTo(30000);
        assertThat(job.getCompanyId()).isEqualTo(9L);
        assertThat(job.getMustHaveSkills()).contains("Java").contains("Redis");
        verify(jobMapper).updateById(job);
        verify(callLogger).log(any(), any(), any(), any(), any(), any(), anyLong(),
                any(Boolean.class), any());
    }

    @Test
    void retryRejectsWhileProcessingAndHandlesQueueRejection() {
        when(jobMapper.selectById(1L)).thenReturn(job("PROCESSING"));
        when(jobMapper.update(any(), any())).thenReturn(0);
        assertCode(() -> service().retryParse(1L), ErrorCode.TASK_IN_PROGRESS);

        when(jobMapper.update(any(), any())).thenReturn(1, 1);
        org.mockito.Mockito.doThrow(new IllegalStateException("full"))
                .when(executor).execute(any(Runnable.class));
        assertCode(() -> service().retryParse(1L), ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void parseMarksFailedAfterRetriesExhausted() {
        JobPosition job = job("PROCESSING");
        when(jobMapper.selectById(1L)).thenReturn(job);
        when(jobMapper.update(any(), any())).thenReturn(1);
        when(sceneService.resolveGlobal()).thenReturn(new LlmSceneService.SceneModel("p", "m"));
        LlmClient client = org.mockito.Mockito.mock(LlmClient.class);
        when(registry.get("p")).thenReturn(client);
        when(client.chatJson(any(), anyList())).thenThrow(new RuntimeException("boom"));
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(executor).execute(any(Runnable.class));

        service().retryParse(1L);

        // 1 次置 PROCESSING + 首次失败后 4 次 finish（末次置 FAILED）
        verify(jobMapper, org.mockito.Mockito.times(5)).update(any(), any());
        verify(callLogger, org.mockito.Mockito.times(4))
                .log(any(), any(), any(), any(), any(), any(), anyLong(), any(Boolean.class), any());
    }

    @Test
    void updateAppliesFieldsAndRejectsInvalidCompanyOrSalaryRange() throws Exception {
        JobPosition job = job("COMPLETED");
        when(jobMapper.selectById(1L)).thenReturn(job);
        when(companyMapper.selectById(5L)).thenReturn(new Company());
        when(companyMapper.selectById(9L)).thenReturn(null);

        var response = service().update(1L, new UpdateJobPositionRequest("高级 Java", MAPPER.readTree("5"),
                "北京", 20000, 30000, "3-5年", "本科", List.of("Java"), List.of("Redis"),
                "负责服务", "APPLIED"));
        assertThat(response.title()).isEqualTo("高级 Java");
        assertThat(response.status()).isEqualTo("APPLIED");
        assertThat(job.getMustHaveSkills()).contains("Java");

        var unknownCompany = MAPPER.readTree("9");
        assertCode(() -> service().update(1L, new UpdateJobPositionRequest(
                null, unknownCompany, null, null, null, null, null, null, null, null, null)),
                ErrorCode.NOT_FOUND);
        assertCode(() -> service().update(1L, new UpdateJobPositionRequest(
                null, null, null, 40000, 10000, null, null, null, null, null, null)),
                ErrorCode.BAD_REQUEST);

        service().update(1L, new UpdateJobPositionRequest(
                null, NullNode.getInstance(), null, null, null, null, null, null, null, null, null));
        assertThat(job.getCompanyId()).isNull();
    }

    @Test
    void detailAndDeleteCleanUpFile() {
        JobPosition job = job("COMPLETED");
        job.setCompanyId(5L);
        job.setStorageKey("job-positions/a.pdf");
        when(jobMapper.selectById(1L)).thenReturn(job);
        Company company = new Company();
        company.setId(5L);
        company.setName("字节跳动");
        when(companyMapper.selectById(5L)).thenReturn(company);

        var detail = service().detail(1L);
        assertThat(detail.companyName()).isEqualTo("字节跳动");
        assertThat(detail.rawText()).isNotBlank();

        service().delete(1L);
        verify(storage).delete("job-positions/a.pdf");
        verify(jobMapper).deleteById(1L);
    }

    @Test
    void batchDeleteIgnoresMissingIds() {
        JobPosition job = job("COMPLETED");
        when(jobMapper.selectByIds(anyList())).thenReturn(List.of(job));

        service().batchDelete(new BatchDeleteJobPositionsRequest(List.of(1L, 2L)));

        verify(jobMapper).deleteByIds(List.of(1L));
    }

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo(expected));
    }
}
