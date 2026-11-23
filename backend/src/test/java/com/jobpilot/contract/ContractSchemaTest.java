package com.jobpilot.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.jobpilot.modules.application.dto.CreateJobApplicationRequest;
import com.jobpilot.modules.application.dto.JobApplicationListItemResponse;
import com.jobpilot.modules.application.dto.JobApplicationTimelineResponse;
import com.jobpilot.modules.application.dto.UpdateJobApplicationRequest;
import com.jobpilot.modules.chat.dto.ChatMessageResponse;
import com.jobpilot.modules.chat.dto.ChatSessionResponse;
import com.jobpilot.modules.chat.dto.ChatStreamEvent;
import com.jobpilot.modules.chat.dto.CreateChatSessionRequest;
import com.jobpilot.modules.chat.dto.SendMessageRequest;
import com.jobpilot.modules.chat.dto.SwitchModelRequest;
import com.jobpilot.modules.company.dto.CompanyResponse;
import com.jobpilot.modules.company.dto.CreateCompanyRequest;
import com.jobpilot.modules.company.dto.UpdateCompanyRequest;
import com.jobpilot.modules.interview.dto.AnswerInterviewQuestionRequest;
import com.jobpilot.modules.interview.dto.CreateInterviewSessionRequest;
import com.jobpilot.modules.interview.dto.InterviewDirection;
import com.jobpilot.modules.interview.dto.InterviewEvaluation;
import com.jobpilot.modules.interview.dto.InterviewEvaluationCategory;
import com.jobpilot.modules.interview.dto.InterviewQuestion;
import com.jobpilot.modules.interview.dto.InterviewSession;
import com.jobpilot.modules.interview.dto.InterviewSessionDetail;
import com.jobpilot.modules.job.dto.BatchDeleteJobPositionsRequest;
import com.jobpilot.modules.job.dto.CountItemResponse;
import com.jobpilot.modules.job.dto.CreateJobMatchRequest;
import com.jobpilot.modules.job.dto.JobMarketInsightsResponse;
import com.jobpilot.modules.job.dto.JobMatchDetailResponse;
import com.jobpilot.modules.job.dto.JobMatchResponse;
import com.jobpilot.modules.job.dto.JobPositionDetailResponse;
import com.jobpilot.modules.job.dto.JobPositionListItemResponse;
import com.jobpilot.modules.job.dto.MatchDimensionResponse;
import com.jobpilot.modules.job.dto.SkillCountItemResponse;
import com.jobpilot.modules.job.dto.UpdateJobPositionRequest;
import com.jobpilot.modules.llm.dto.BindSceneModelRequest;
import com.jobpilot.modules.llm.dto.ConnectivityTestResponse;
import com.jobpilot.modules.llm.dto.CreateModelRequest;
import com.jobpilot.modules.llm.dto.CreateProviderRequest;
import com.jobpilot.modules.llm.dto.GlobalSettingResponse;
import com.jobpilot.modules.llm.dto.LlmModelResponse;
import com.jobpilot.modules.llm.dto.LlmProviderResponse;
import com.jobpilot.modules.llm.dto.SceneBindingResponse;
import com.jobpilot.modules.llm.dto.TestProviderRequest;
import com.jobpilot.modules.llm.dto.UpdateGlobalSettingRequest;
import com.jobpilot.modules.llm.dto.UpdateModelRequest;
import com.jobpilot.modules.llm.dto.UpdateProviderRequest;
import com.jobpilot.modules.llm.dto.UpstreamModelListResponse;
import com.jobpilot.modules.offer.dto.AnalyzeOfferRequest;
import com.jobpilot.modules.offer.dto.CreateOfferRequest;
import com.jobpilot.modules.offer.dto.Offer;
import com.jobpilot.modules.offer.dto.OfferAnalysis;
import com.jobpilot.modules.offer.dto.OfferComparison;
import com.jobpilot.modules.offer.dto.OfferComparisonColumn;
import com.jobpilot.modules.offer.dto.OfferComparisonRow;
import com.jobpilot.modules.offer.dto.OfferDetail;
import com.jobpilot.modules.offer.dto.OfferExtraction;
import com.jobpilot.modules.offer.dto.UpdateOfferRequest;
import com.jobpilot.modules.resume.dto.AnalysisDimensionResponse;
import com.jobpilot.modules.resume.dto.CareerDirectionResponse;
import com.jobpilot.modules.resume.dto.ComparisonSideResponse;
import com.jobpilot.modules.resume.dto.ComparisonSummaryResponse;
import com.jobpilot.modules.resume.dto.CreateResumeComparisonRequest;
import com.jobpilot.modules.resume.dto.DimensionDeltaResponse;
import com.jobpilot.modules.resume.dto.QuoteItemResponse;
import com.jobpilot.modules.resume.dto.ResumeAnalysisReportResponse;
import com.jobpilot.modules.resume.dto.ResumeAnalysisStatusResponse;
import com.jobpilot.modules.resume.dto.ResumeBasicInfoResponse;
import com.jobpilot.modules.resume.dto.ResumeComparisonResponse;
import com.jobpilot.modules.resume.dto.ResumeDetailResponse;
import com.jobpilot.modules.resume.dto.ResumeListItemResponse;
import com.jobpilot.modules.resume.dto.ResumeUploadResponse;
import com.jobpilot.modules.resume.dto.SuggestionItemResponse;
import com.jobpilot.modules.resume.dto.UpdateSalaryRequest;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * 契约 schema 与 DTO 字段对齐检查：契约改了字段而实现没跟（或反之）时直接失败。
 *
 * <p>按「schema 名 = DTO 简单名，或 DTO 去 Response 后缀」匹配；Result 包装、枚举等无对应 DTO 的 schema 跳过。
 */
class ContractSchemaTest {

    private static final List<Class<?>> DTOS = List.of(
            LlmProviderResponse.class, LlmModelResponse.class, CreateProviderRequest.class,
            UpdateProviderRequest.class, CreateModelRequest.class, UpdateModelRequest.class,
            TestProviderRequest.class, ConnectivityTestResponse.class, UpstreamModelListResponse.class,
            GlobalSettingResponse.class, UpdateGlobalSettingRequest.class,
            ChatSessionResponse.class, CreateChatSessionRequest.class, SwitchModelRequest.class,
            ChatMessageResponse.class, SendMessageRequest.class, ChatStreamEvent.class,
            ResumeListItemResponse.class, ResumeUploadResponse.class, ResumeDetailResponse.class,
            ResumeAnalysisStatusResponse.class, ResumeAnalysisReportResponse.class,
            AnalysisDimensionResponse.class, ResumeBasicInfoResponse.class, UpdateSalaryRequest.class,
            QuoteItemResponse.class, SuggestionItemResponse.class, CareerDirectionResponse.class,
            SceneBindingResponse.class, BindSceneModelRequest.class,
            CompanyResponse.class, CreateCompanyRequest.class, UpdateCompanyRequest.class,
            JobPositionListItemResponse.class, JobPositionDetailResponse.class,
            UpdateJobPositionRequest.class, BatchDeleteJobPositionsRequest.class,
            CreateJobMatchRequest.class, MatchDimensionResponse.class, JobMatchDetailResponse.class,
            JobMatchResponse.class, CountItemResponse.class, SkillCountItemResponse.class,
            JobMarketInsightsResponse.class, CreateResumeComparisonRequest.class,
            ComparisonSideResponse.class, DimensionDeltaResponse.class,
            ComparisonSummaryResponse.class, ResumeComparisonResponse.class,
            InterviewDirection.class, InterviewQuestion.class, InterviewSession.class,
            InterviewSessionDetail.class, CreateInterviewSessionRequest.class,
            AnswerInterviewQuestionRequest.class, InterviewEvaluationCategory.class, InterviewEvaluation.class,
            Offer.class, OfferDetail.class, CreateOfferRequest.class, UpdateOfferRequest.class,
            OfferExtraction.class, AnalyzeOfferRequest.class, OfferComparisonColumn.class,
            OfferComparisonRow.class, OfferComparison.class, OfferAnalysis.class,
            JobApplicationListItemResponse.class, CreateJobApplicationRequest.class,
            UpdateJobApplicationRequest.class, JobApplicationTimelineResponse.class);

    @Test
    @SuppressWarnings("unchecked")
    void dtoFieldsMatchContractSchemas() throws Exception {
        Map<String, Object> schemas = loadSchemas();
        int checked = 0;
        for (Class<?> dto : DTOS) {
            String simple = dto.getSimpleName();
            Object raw = schemas.get(simple);
            if (raw == null && simple.endsWith("Response")) {
                raw = schemas.get(simple.substring(0, simple.length() - "Response".length()));
            }
            if (!(raw instanceof Map<?, ?> schema)) {
                continue;
            }
            Set<String> contractFields = new TreeSet<>(properties(schemas, (Map<String, Object>) schema).keySet());
            Set<String> dtoFields = Arrays.stream(dto.getRecordComponents())
                    .map(RecordComponent::getName).collect(Collectors.toCollection(TreeSet::new));
            assertThat(dtoFields)
                    .as("%s 与契约 %s 字段不一致", simple, schemaName(schemas, simple))
                    .isEqualTo(contractFields);
            checked++;
        }
        // 不允许任何 DTO 因命名规则失效被静默跳过
        assertThat(checked).isEqualTo(DTOS.size());
    }

    private String schemaName(Map<String, Object> schemas, String simple) {
        return schemas.containsKey(simple) ? simple
                : simple.substring(0, simple.length() - "Response".length());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> properties(Map<String, Object> schemas, Map<String, Object> schema) {
        Map<String, Object> merged = new LinkedHashMap<>();
        Object allOf = schema.get("allOf");
        if (allOf instanceof List<?> parts) {
            for (Object part : parts) {
                Map<String, Object> node = (Map<String, Object>) part;
                Object ref = node.get("$ref");
                if (ref instanceof String path) {
                    String name = path.substring(path.lastIndexOf('/') + 1);
                    merged.putAll(properties(schemas, (Map<String, Object>) schemas.get(name)));
                } else {
                    merged.putAll(properties(schemas, node));
                }
            }
        }
        Object own = schema.get("properties");
        if (own instanceof Map<?, ?> props) {
            merged.putAll((Map<String, Object>) props);
        }
        return merged;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadSchemas() throws Exception {
        Path contract = Path.of("..", "contracts", "openapi.yaml");
        try (InputStream in = Files.newInputStream(contract)) {
            Map<String, Object> doc = new Yaml().load(in);
            Map<String, Object> components = (Map<String, Object>) doc.get("components");
            return (Map<String, Object>) components.get("schemas");
        }
    }
}
