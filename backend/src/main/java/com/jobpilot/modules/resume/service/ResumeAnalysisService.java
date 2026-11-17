package com.jobpilot.modules.resume.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import com.jobpilot.modules.llm.entity.LlmGlobalSetting;
import com.jobpilot.modules.llm.mapper.LlmGlobalSettingMapper;
import com.jobpilot.modules.resume.ai.AiResumeAnalysis;
import com.jobpilot.modules.resume.dto.AnalysisDimensionResponse;
import com.jobpilot.modules.resume.dto.CareerDirectionResponse;
import com.jobpilot.modules.resume.dto.QuoteItemResponse;
import com.jobpilot.modules.resume.dto.ResumeAnalysisReportResponse;
import com.jobpilot.modules.resume.dto.ResumeAnalysisStatusResponse;
import com.jobpilot.modules.resume.dto.ResumeBasicInfoResponse;
import com.jobpilot.modules.resume.dto.SuggestionItemResponse;
import com.jobpilot.modules.resume.entity.Resume;
import com.jobpilot.modules.resume.entity.ResumeAnalysis;
import com.jobpilot.modules.resume.mapper.ResumeAnalysisMapper;
import com.jobpilot.modules.resume.mapper.ResumeMapper;
import com.jobpilot.modules.resume.prompt.ResumePromptFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

/**
 * 简历异步分析（FR1.5 / FR1.6 / FR1.7）：状态机 + 失败重试 ≤ 3 + 五维加权报告。
 *
 * <p>总分由本地按权重计算（技术深度 30 / 项目质量 25 / 工程能力 20 / 表达结构 15 / 岗位匹配 10），
 * 不信任模型自报总分；模型输出缺失或越界会触发带错误反馈重试。
 */
@Service
public class ResumeAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(ResumeAnalysisService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final int MAX_RETRIES = 3;

    private final ResumeMapper resumeMapper;
    private final ResumeAnalysisMapper analysisMapper;
    private final LlmGlobalSettingMapper globalMapper;
    private final ProviderClientRegistry registry;
    private final LlmCallLogger callLogger;
    private final ResumePromptFactory prompts;
    private final ThreadPoolTaskExecutor executor;

    public ResumeAnalysisService(ResumeMapper resumeMapper, ResumeAnalysisMapper analysisMapper,
                                 LlmGlobalSettingMapper globalMapper, ProviderClientRegistry registry,
                                 LlmCallLogger callLogger, ResumePromptFactory prompts,
                                 @Qualifier("jobpilotTaskExecutor") ThreadPoolTaskExecutor executor) {
        this.resumeMapper = resumeMapper;
        this.analysisMapper = analysisMapper;
        this.globalMapper = globalMapper;
        this.registry = registry;
        this.callLogger = callLogger;
        this.prompts = prompts;
        this.executor = executor;
    }

    /** 手动触发 / 重试：PROCESSING 时 409；失败重试会重置 retryCount。 */
    public ResumeAnalysisStatusResponse trigger(Long resumeId) {
        requireResume(resumeId);
        if (defaultModel() == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST,
                    "尚未设置默认模型：请打开左下角「设置 → 模型服务」，将某个模型设为全局默认后再分析");
        }
        int updated = resumeMapper.update(null, Wrappers.<Resume>lambdaUpdate()
                .eq(Resume::getId, resumeId)
                .ne(Resume::getAnalyzeStatus, "PROCESSING")
                .set(Resume::getAnalyzeStatus, "PROCESSING")
                .set(Resume::getRetryCount, 0)
                .set(Resume::getAnalyzeError, null)
                .set(Resume::getUpdatedAt, OffsetDateTime.now()));
        if (updated == 0) {
            throw BizException.of(ErrorCode.ANALYSIS_IN_PROGRESS);
        }
        try {
            submit(resumeId);
        } catch (RuntimeException ex) {
            // 队列拒绝时回滚状态，避免简历永久停留在 PROCESSING
            resetToPending(resumeId, "分析任务队列已满，请稍后重试");
            log.warn("简历分析任务入队失败 resumeId={}", resumeId, ex);
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "分析任务队列已满，请稍后重试");
        }
        return new ResumeAnalysisStatusResponse(resumeId, "PROCESSING", null, 0, null);
    }

    /** 上传后的静默触发：仅在已配置全局默认且简历仍为 PENDING 时执行。 */
    public void autoTrigger(Long resumeId) {
        try {
            if (defaultModel() == null) {
                return;
            }
            int updated = resumeMapper.update(null, Wrappers.<Resume>lambdaUpdate()
                    .eq(Resume::getId, resumeId)
                    .eq(Resume::getAnalyzeStatus, "PENDING")
                    .set(Resume::getAnalyzeStatus, "PROCESSING")
                    .set(Resume::getRetryCount, 0)
                    .set(Resume::getUpdatedAt, OffsetDateTime.now()));
            if (updated > 0) {
                try {
                    submit(resumeId);
                } catch (RuntimeException ex) {
                    resetToPending(resumeId, "分析任务队列已满，请稍后重试");
                    log.warn("自动触发简历分析入队失败 resumeId={}", resumeId, ex);
                }
            }
        } catch (Exception ex) {
            log.warn("自动触发简历分析失败 resumeId={}", resumeId, ex);
        }
    }

    /** 轮询端点：PENDING/PROCESSING 时 report 为 null。 */
    public ResumeAnalysisStatusResponse status(Long resumeId) {
        Resume resume = requireResume(resumeId);
        ResumeAnalysis analysis = analysisMapper.selectOne(Wrappers.<ResumeAnalysis>lambdaQuery()
                .eq(ResumeAnalysis::getResumeId, resumeId));
        ResumeAnalysisReportResponse report = "COMPLETED".equals(resume.getAnalyzeStatus()) && analysis != null
                ? toReport(analysis, resume) : null;
        return new ResumeAnalysisStatusResponse(resumeId, resume.getAnalyzeStatus(),
                resume.getAnalyzeError(), resume.getRetryCount(), report);
    }

    /** 提交异步任务并透传请求线程的 traceId，保证 llm_call_logs 可串联。 */
    private void submit(Long resumeId) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        executor.execute(() -> {
            if (context != null) {
                MDC.setContextMap(context);
            }
            try {
                analyze(resumeId);
            } finally {
                MDC.clear();
            }
        });
    }

    /** 异步 worker：首次 + 最多 3 次重试。 */
    private void analyze(Long resumeId) {
        Resume resume = resumeMapper.selectById(resumeId);
        if (resume == null || !"PROCESSING".equals(resume.getAnalyzeStatus())) {
            return;
        }
        DefaultModel target = defaultModel();
        if (target == null) {
            finish(resumeId, "FAILED", "未配置全局默认 Provider/Model", 0);
            return;
        }
        LlmClient client;
        try {
            client = registry.get(target.providerKey());
        } catch (Exception ex) {
            finish(resumeId, "FAILED", truncate(ex.getMessage()), 0);
            return;
        }
        StructuredOutputInvoker invoker = new StructuredOutputInvoker(client);
        List<ChatMessage> messages = List.of(
                ChatMessage.system(prompts.system()),
                ChatMessage.user(prompts.user(resume.getResumeText())));

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (resumeMapper.selectById(resumeId) == null) {
                log.info("简历 {} 已删除，终止分析", resumeId);
                return;
            }
            long start = System.nanoTime();
            try {
                StructuredOutputInvoker.Parsed<AiResumeAnalysis> parsed =
                        invoker.invokeWithUsage(target.model(), messages, AiResumeAnalysis.class);
                saveAnalysis(resumeId, parsed.value(), target);
                resumeMapper.update(null, Wrappers.<Resume>lambdaUpdate()
                        .eq(Resume::getId, resumeId)
                        .set(Resume::getAnalyzeStatus, "COMPLETED")
                        .set(Resume::getRetryCount, attempt)
                        .set(Resume::getAnalyzeError, null)
                        .set(Resume::getUpdatedAt, OffsetDateTime.now()));
                ChatResult usage = parsed.result();
                callLogger.log("RESUME_ANALYSIS", target.providerKey(), target.model(),
                        usage.promptTokens(), usage.completionTokens(), usage.totalTokens(),
                        elapsed(start), true, null);
                return;
            } catch (Exception ex) {
                if (resumeMapper.selectById(resumeId) == null) {
                    log.info("简历 {} 已删除，终止分析", resumeId);
                    return;
                }
                int retries = Math.min(attempt + 1, MAX_RETRIES);
                finish(resumeId, attempt == MAX_RETRIES ? "FAILED" : "PROCESSING",
                        truncate(ex.getMessage()), retries);
                callLogger.log("RESUME_ANALYSIS", target.providerKey(), target.model(),
                        null, null, null, elapsed(start), false, ex.getMessage());
                log.warn("简历分析失败 resumeId={} 第 {} 次: {}", resumeId, attempt + 1, ex.getMessage());
            }
        }
    }

    private void saveAnalysis(Long resumeId, AiResumeAnalysis ai, DefaultModel target) throws Exception {
        int technical = score(ai.technicalDepth());
        int project = score(ai.projectQuality());
        int engineering = score(ai.engineeringAbility());
        int expression = score(ai.expressionStructure());
        int match = score(ai.jobMatchPotential());
        int overall = (int) Math.round(technical * 0.30 + project * 0.25
                + engineering * 0.20 + expression * 0.15 + match * 0.10);

        ResumeAnalysis entity = analysisMapper.selectOne(Wrappers.<ResumeAnalysis>lambdaQuery()
                .eq(ResumeAnalysis::getResumeId, resumeId));
        boolean exists = entity != null;
        if (!exists) {
            entity = new ResumeAnalysis();
            entity.setUserId(1L);
            entity.setResumeId(resumeId);
        }
        entity.setOverallScore(overall);
        entity.setScoreTechnicalDepth(technical);
        entity.setScoreProjectQuality(project);
        entity.setScoreEngineeringAbility(engineering);
        entity.setScoreExpressionStructure(expression);
        entity.setScoreJobMatchPotential(match);
        entity.setBasicInfo(ai.basicInfo() == null ? null : MAPPER.writeValueAsString(ai.basicInfo()));
        entity.setStrengths(MAPPER.writeValueAsString(listOrEmpty(ai.strengths())));
        entity.setWeaknesses(MAPPER.writeValueAsString(listOrEmpty(ai.weaknesses())));
        entity.setSuggestions(MAPPER.writeValueAsString(listOrEmpty(ai.suggestions())));
        entity.setCareerDirections(MAPPER.writeValueAsString(
                ai.careerDirections() == null ? List.of()
                        : ai.careerDirections().stream().limit(5).toList()));
        entity.setLlmProvider(target.providerKey());
        entity.setLlmModel(target.model());
        entity.setPromptVersion(ResumePromptFactory.VERSION);
        entity.setUpdatedAt(OffsetDateTime.now());
        if (exists) {
            analysisMapper.updateById(entity);
        } else {
            analysisMapper.insert(entity);
        }
    }

    private ResumeAnalysisReportResponse toReport(ResumeAnalysis entity, Resume resume) {
        List<AnalysisDimensionResponse> dimensions = List.of(
                new AnalysisDimensionResponse("technicalDepth", "技术深度",
                        entity.getScoreTechnicalDepth(), 30),
                new AnalysisDimensionResponse("projectQuality", "项目质量",
                        entity.getScoreProjectQuality(), 25),
                new AnalysisDimensionResponse("engineeringAbility", "工程能力",
                        entity.getScoreEngineeringAbility(), 20),
                new AnalysisDimensionResponse("expressionStructure", "表达结构",
                        entity.getScoreExpressionStructure(), 15),
                new AnalysisDimensionResponse("jobMatchPotential", "岗位匹配潜力",
                        entity.getScoreJobMatchPotential(), 10));
        return new ResumeAnalysisReportResponse(entity.getOverallScore(), dimensions,
                mergedBasicInfo(entity, resume),
                parseList(entity.getStrengths(), new TypeReference<List<QuoteItemResponse>>() { }),
                parseList(entity.getWeaknesses(), new TypeReference<List<QuoteItemResponse>>() { }),
                parseList(entity.getSuggestions(), new TypeReference<List<SuggestionItemResponse>>() { }),
                parseList(entity.getCareerDirections(), new TypeReference<List<CareerDirectionResponse>>() { }),
                entity.getLlmProvider(), entity.getLlmModel());
    }

    /** 用户填写的薪资优先于 LLM 提取值；两者都没有时返回 null。 */
    private ResumeBasicInfoResponse mergedBasicInfo(ResumeAnalysis entity, Resume resume) {
        ResumeBasicInfoResponse extracted = parse(entity.getBasicInfo(),
                new TypeReference<ResumeBasicInfoResponse>() { });
        Integer current = resume.getCurrentSalary() != null ? resume.getCurrentSalary()
                : extracted == null ? null : extracted.currentMonthlySalary();
        Integer expected = resume.getExpectedSalary() != null ? resume.getExpectedSalary()
                : extracted == null ? null : extracted.expectedMonthlySalary();
        if (extracted == null) {
            return current == null && expected == null ? null
                    : new ResumeBasicInfoResponse(null, null, null, current, expected);
        }
        return new ResumeBasicInfoResponse(extracted.name(), extracted.yearsOfExperience(),
                extracted.techDirection(), current, expected);
    }

    private void finish(Long resumeId, String status, String error, int retryCount) {
        resumeMapper.update(null, Wrappers.<Resume>lambdaUpdate()
                .eq(Resume::getId, resumeId)
                .set(Resume::getAnalyzeStatus, status)
                .set(Resume::getAnalyzeError, truncate(error))
                .set(Resume::getRetryCount, retryCount)
                .set(Resume::getUpdatedAt, OffsetDateTime.now()));
    }

    /** 仅把仍在 PROCESSING 的简历回退为 PENDING，避免覆盖已经完成或失败的结果。 */
    private void resetToPending(Long resumeId, String error) {
        resumeMapper.update(null, Wrappers.<Resume>lambdaUpdate()
                .eq(Resume::getId, resumeId)
                .eq(Resume::getAnalyzeStatus, "PROCESSING")
                .set(Resume::getAnalyzeStatus, "PENDING")
                .set(Resume::getAnalyzeError, truncate(error))
                .set(Resume::getUpdatedAt, OffsetDateTime.now()));
    }

    private Resume requireResume(Long resumeId) {
        Resume resume = resumeMapper.selectById(resumeId);
        if (resume == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "简历不存在: " + resumeId);
        }
        return resume;
    }

    private DefaultModel defaultModel() {
        LlmGlobalSetting global = globalMapper.selectById(1);
        if (global == null || isBlank(global.getDefaultProviderKey()) || isBlank(global.getDefaultModel())) {
            return null;
        }
        return new DefaultModel(global.getDefaultProviderKey(), global.getDefaultModel());
    }

    private static int score(Integer value) {
        if (value == null) {
            throw new IllegalArgumentException("模型输出缺少评分字段");
        }
        return Math.max(0, Math.min(100, value));
    }

    private static <T> List<T> listOrEmpty(List<T> value) {
        return value == null ? List.of() : value;
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

    private static <T> T parse(String json, TypeReference<T> reference) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, reference);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 500 ? text : text.substring(0, 500);
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record DefaultModel(String providerKey, String model) {
    }
}
