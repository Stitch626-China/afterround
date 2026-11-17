package com.jobpilot.modules.resume.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.common.llm.ChatMessage;
import com.jobpilot.common.llm.ChatResult;
import com.jobpilot.common.llm.LlmClient;
import com.jobpilot.common.llm.StructuredOutputInvoker;
import com.jobpilot.infrastructure.llm.LlmCallLogger;
import com.jobpilot.infrastructure.llm.ProviderClientRegistry;
import com.jobpilot.modules.llm.service.LlmSceneService;
import com.jobpilot.modules.resume.ai.AiResumeComparison;
import com.jobpilot.modules.resume.dto.CareerDirectionResponse;
import com.jobpilot.modules.resume.dto.ComparisonSideResponse;
import com.jobpilot.modules.resume.dto.ComparisonSummaryResponse;
import com.jobpilot.modules.resume.dto.CreateResumeComparisonRequest;
import com.jobpilot.modules.resume.dto.DimensionDeltaResponse;
import com.jobpilot.modules.resume.dto.QuoteItemResponse;
import com.jobpilot.modules.resume.dto.ResumeComparisonResponse;
import com.jobpilot.modules.resume.entity.Resume;
import com.jobpilot.modules.resume.entity.ResumeAnalysis;
import com.jobpilot.modules.resume.mapper.ResumeAnalysisMapper;
import com.jobpilot.modules.resume.mapper.ResumeMapper;
import com.jobpilot.modules.resume.prompt.ResumeComparisonPromptFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 简历版本对比（FR7）：分数、五维、优势短板与方向差异由本地计算，LLM 只生成一段点评。
 *
 * <p>不落库、不产生历史记录；两份分析的模型或 Prompt 版本不同时标注仅供参考；
 * LLM 失败降级为 {@code degraded = true}，本地对比结果照常返回（§6.5 分层降级）。
 */
@Service
public class ResumeComparisonService {

    private static final Logger log = LoggerFactory.getLogger(ResumeComparisonService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ResumeMapper resumeMapper;
    private final ResumeAnalysisMapper analysisMapper;
    private final LlmSceneService sceneService;
    private final ProviderClientRegistry registry;
    private final LlmCallLogger callLogger;
    private final ResumeComparisonPromptFactory prompts;

    public ResumeComparisonService(ResumeMapper resumeMapper, ResumeAnalysisMapper analysisMapper,
                                   LlmSceneService sceneService, ProviderClientRegistry registry,
                                   LlmCallLogger callLogger, ResumeComparisonPromptFactory prompts) {
        this.resumeMapper = resumeMapper;
        this.analysisMapper = analysisMapper;
        this.sceneService = sceneService;
        this.registry = registry;
        this.callLogger = callLogger;
        this.prompts = prompts;
    }

    public ResumeComparisonResponse compare(CreateResumeComparisonRequest request) {
        if (request.baseResumeId().equals(request.targetResumeId())) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "两份简历不能相同");
        }
        Side base = side(request.baseResumeId());
        Side target = side(request.targetResumeId());
        List<DimensionDeltaResponse> deltas = deltas(base.analysis(), target.analysis());
        List<QuoteItemResponse> strengthsAdded = added(base.strengths(), target.strengths());
        List<QuoteItemResponse> strengthsRemoved = added(target.strengths(), base.strengths());
        List<QuoteItemResponse> weaknessesAdded = added(base.weaknesses(), target.weaknesses());
        List<QuoteItemResponse> weaknessesRemoved = added(target.weaknesses(), base.weaknesses());
        List<String> directionsAdded = addedStrings(base.directions(), target.directions());
        List<String> directionsRemoved = addedStrings(target.directions(), base.directions());

        ComparisonSummaryResponse summary = summarize(base, target, deltas,
                strengthsAdded, strengthsRemoved, weaknessesAdded, weaknessesRemoved,
                directionsAdded, directionsRemoved);

        return new ResumeComparisonResponse(base.toSide(), target.toSide(),
                target.analysis().getOverallScore() - base.analysis().getOverallScore(), deltas,
                strengthsAdded, strengthsRemoved, weaknessesAdded, weaknessesRemoved,
                directionsAdded, directionsRemoved, summary);
    }

    /** 读取一份简历及其分析；任一分析未完成返回 409（40907）。 */
    private Side side(Long resumeId) {
        Resume resume = resumeMapper.selectById(resumeId);
        if (resume == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "简历不存在: " + resumeId);
        }
        ResumeAnalysis analysis = analysisMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<ResumeAnalysis>lambdaQuery()
                        .eq(ResumeAnalysis::getResumeId, resumeId));
        if (!"COMPLETED".equals(resume.getAnalyzeStatus()) || analysis == null
                || analysis.getOverallScore() == null) {
            throw BizException.of(ErrorCode.ANALYSIS_NOT_COMPLETED,
                    "简历「" + (resume.getFileName() == null ? resumeId : resume.getFileName()) + "」分析未完成");
        }
        return new Side(resume, analysis,
                parseList(analysis.getStrengths(), new TypeReference<List<QuoteItemResponse>>() { }),
                parseList(analysis.getWeaknesses(), new TypeReference<List<QuoteItemResponse>>() { }),
                parseList(analysis.getCareerDirections(), new TypeReference<List<CareerDirectionResponse>>() { }));
    }

    private List<DimensionDeltaResponse> deltas(ResumeAnalysis base, ResumeAnalysis target) {
        return List.of(
                delta("technicalDepth", "技术深度", base.getScoreTechnicalDepth(), target.getScoreTechnicalDepth()),
                delta("projectQuality", "项目质量", base.getScoreProjectQuality(), target.getScoreProjectQuality()),
                delta("engineeringAbility", "工程能力",
                        base.getScoreEngineeringAbility(), target.getScoreEngineeringAbility()),
                delta("expressionStructure", "表达结构",
                        base.getScoreExpressionStructure(), target.getScoreExpressionStructure()),
                delta("jobMatchPotential", "岗位匹配潜力",
                        base.getScoreJobMatchPotential(), target.getScoreJobMatchPotential()));
    }

    private static DimensionDeltaResponse delta(String key, String label, Integer base, Integer target) {
        int b = base == null ? 0 : base;
        int t = target == null ? 0 : target;
        return new DimensionDeltaResponse(key, label, b, t, t - b);
    }

    /** target 中按 quote 文本存在、base 中没有的条目。 */
    private static List<QuoteItemResponse> added(List<QuoteItemResponse> base, List<QuoteItemResponse> target) {
        Set<String> existing = base.stream().map(item -> normalize(item.quote()))
                .collect(Collectors.toSet());
        return target.stream().filter(item -> !existing.contains(normalize(item.quote())))
                .limit(20).toList();
    }

    private static List<String> addedStrings(List<CareerDirectionResponse> base,
                                             List<CareerDirectionResponse> target) {
        Set<String> existing = base.stream().map(item -> normalize(item.direction()))
                .collect(Collectors.toSet());
        LinkedHashSet<String> result = new LinkedHashSet<>();
        target.stream().map(CareerDirectionResponse::direction)
                .filter(direction -> direction != null && !direction.isBlank())
                .filter(direction -> !existing.contains(normalize(direction)))
                .forEach(result::add);
        return new ArrayList<>(result);
    }

    /** LLM 点评：失败时降级，不影响本地对比结果。 */
    private ComparisonSummaryResponse summarize(Side base, Side target, List<DimensionDeltaResponse> deltas,
                                                List<QuoteItemResponse> strengthsAdded,
                                                List<QuoteItemResponse> strengthsRemoved,
                                                List<QuoteItemResponse> weaknessesAdded,
                                                List<QuoteItemResponse> weaknessesRemoved,
                                                List<String> directionsAdded, List<String> directionsRemoved) {
        boolean modelMismatch = !Objects.equals(base.analysis().getLlmProvider(), target.analysis().getLlmProvider())
                || !Objects.equals(base.analysis().getLlmModel(), target.analysis().getLlmModel());
        boolean promptVersionMismatch = !Objects.equals(
                base.analysis().getPromptVersion(), target.analysis().getPromptVersion());
        try {
            LlmSceneService.SceneModel scene = sceneService.resolveGlobal();
            LlmClient client = registry.get(scene.providerKey());
            List<ChatMessage> messages = List.of(
                    ChatMessage.system(prompts.system()),
                    ChatMessage.user(prompts.user(describe(base), describe(target),
                            describeDiff(deltas, strengthsAdded, strengthsRemoved,
                                    weaknessesAdded, weaknessesRemoved, directionsAdded, directionsRemoved))));
            long start = System.nanoTime();
            StructuredOutputInvoker.Parsed<AiResumeComparison> parsed =
                    new StructuredOutputInvoker(client).invokeWithUsage(
                            scene.model(), messages, AiResumeComparison.class);
            ChatResult usage = parsed.result();
            callLogger.log("RESUME_COMPARISON", scene.providerKey(), scene.model(),
                    usage.promptTokens(), usage.completionTokens(), usage.totalTokens(),
                    (System.nanoTime() - start) / 1_000_000, true, null);
            return new ComparisonSummaryResponse(parsed.value().summary(),
                    modelMismatch, promptVersionMismatch, false, null);
        } catch (Exception ex) {
            log.warn("简历对比总结生成失败，降级为本地结果: {}", ex.getMessage());
            callLogger.log("RESUME_COMPARISON", null, null,
                    null, null, null, 0, false, ex.getMessage());
            return new ComparisonSummaryResponse(null, modelMismatch, promptVersionMismatch, true,
                    truncate(ex.getMessage(), 200));
        }
    }

    private static String describe(Side side) {
        StringBuilder builder = new StringBuilder();
        builder.append("文件名：").append(side.resume().getFileName() == null ? "粘贴文本" : side.resume().getFileName())
                .append("；总分：").append(side.analysis().getOverallScore()).append('\n');
        builder.append("优势：").append(side.strengths().stream().map(QuoteItemResponse::quote)
                .collect(Collectors.joining("；"))).append('\n');
        builder.append("短板：").append(side.weaknesses().stream().map(QuoteItemResponse::quote)
                .collect(Collectors.joining("；"))).append('\n');
        builder.append("推荐方向：").append(side.directions().stream().map(CareerDirectionResponse::direction)
                .collect(Collectors.joining("、")));
        return builder.toString();
    }

    private static String describeDiff(List<DimensionDeltaResponse> deltas,
                                       List<QuoteItemResponse> strengthsAdded,
                                       List<QuoteItemResponse> strengthsRemoved,
                                       List<QuoteItemResponse> weaknessesAdded,
                                       List<QuoteItemResponse> weaknessesRemoved,
                                       List<String> directionsAdded, List<String> directionsRemoved) {
        return "五维变化：" + deltas.stream()
                .map(delta -> delta.label() + " " + delta.delta())
                .collect(Collectors.joining("；"))
                + "\n新增优势：" + quotes(strengthsAdded)
                + "\n减少优势：" + quotes(strengthsRemoved)
                + "\n新增短板：" + quotes(weaknessesAdded)
                + "\n消除短板：" + quotes(weaknessesRemoved)
                + "\n新增方向：" + String.join("、", directionsAdded)
                + "\n移除方向：" + String.join("、", directionsRemoved);
    }

    private static String quotes(List<QuoteItemResponse> items) {
        return items.stream().map(QuoteItemResponse::quote).collect(Collectors.joining("；"));
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ");
    }

    private static <T> List<T> parseList(String json, TypeReference<List<T>> reference) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, reference);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** 单份简历的分析快照，避免 compare 里重复取数。 */
    private record Side(Resume resume, ResumeAnalysis analysis,
                        List<QuoteItemResponse> strengths, List<QuoteItemResponse> weaknesses,
                        List<CareerDirectionResponse> directions) {

        ComparisonSideResponse toSide() {
            return new ComparisonSideResponse(resume.getId(), resume.getFileName(), analysis.getOverallScore(),
                    analysis.getUpdatedAt(), analysis.getLlmProvider(), analysis.getLlmModel(),
                    analysis.getPromptVersion());
        }
    }
}
