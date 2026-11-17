package com.jobpilot.modules.job.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.api.PageResult;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.common.llm.ChatMessage;
import com.jobpilot.common.llm.ChatResult;
import com.jobpilot.common.llm.LlmClient;
import com.jobpilot.common.llm.StructuredOutputInvoker;
import com.jobpilot.infrastructure.llm.LlmCallLogger;
import com.jobpilot.infrastructure.llm.ProviderClientRegistry;
import com.jobpilot.modules.company.entity.Company;
import com.jobpilot.modules.company.mapper.CompanyMapper;
import com.jobpilot.modules.job.ai.AiJobMatch;
import com.jobpilot.modules.job.dto.CreateJobMatchRequest;
import com.jobpilot.modules.job.dto.JobMatchDetailResponse;
import com.jobpilot.modules.job.dto.JobMatchResponse;
import com.jobpilot.modules.job.dto.MatchDimensionResponse;
import com.jobpilot.modules.job.entity.JobMatchResult;
import com.jobpilot.modules.job.entity.JobPosition;
import com.jobpilot.modules.job.mapper.JobMatchResultMapper;
import com.jobpilot.modules.job.mapper.JobPositionMapper;
import com.jobpilot.modules.job.prompt.JobMatchPromptFactory;
import com.jobpilot.modules.llm.dto.LlmScene;
import com.jobpilot.modules.llm.service.LlmSceneService;
import com.jobpilot.modules.resume.entity.Resume;
import com.jobpilot.modules.resume.mapper.ResumeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

/**
 * 简历-JD 匹配评分（FR2.3）：场景 JOB_MATCH 绑定模型，异步执行，三项权重本地固定 50/30/20。
 *
 * <p>同一 (resumeId, jobPositionId) 唯一：重复发起表示重跑，覆盖原结果并重置 retryCount；
 * 执行中重复发起返回 409（40908）。
 */
@Service
public class JobMatchService {

    private static final Logger log = LoggerFactory.getLogger(JobMatchService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final int MAX_RETRIES = 3;
    private static final int MAX_RESUME_CHARS = 20_000;
    private static final int MAX_JD_CHARS = 8_000;

    private final JobMatchResultMapper matchMapper;
    private final JobPositionMapper jobMapper;
    private final CompanyMapper companyMapper;
    private final ResumeMapper resumeMapper;
    private final LlmSceneService sceneService;
    private final ProviderClientRegistry registry;
    private final LlmCallLogger callLogger;
    private final JobMatchPromptFactory prompts;
    private final ThreadPoolTaskExecutor executor;

    public JobMatchService(JobMatchResultMapper matchMapper, JobPositionMapper jobMapper,
                           CompanyMapper companyMapper, ResumeMapper resumeMapper,
                           LlmSceneService sceneService, ProviderClientRegistry registry,
                           LlmCallLogger callLogger, JobMatchPromptFactory prompts,
                           @Qualifier("jobpilotTaskExecutor") ThreadPoolTaskExecutor executor) {
        this.matchMapper = matchMapper;
        this.jobMapper = jobMapper;
        this.companyMapper = companyMapper;
        this.resumeMapper = resumeMapper;
        this.sceneService = sceneService;
        this.registry = registry;
        this.callLogger = callLogger;
        this.prompts = prompts;
        this.executor = executor;
    }

    public PageResult<JobMatchResponse> page(Integer page, Integer size, Long resumeId, Long jobPositionId) {
        int current = PageResult.normalizePage(page);
        int pageSize = PageResult.normalizeSize(size);
        Page<JobMatchResult> result = matchMapper.selectPage(new Page<>(current, pageSize),
                Wrappers.<JobMatchResult>lambdaQuery()
                        .eq(resumeId != null, JobMatchResult::getResumeId, resumeId)
                        .eq(jobPositionId != null, JobMatchResult::getJobPositionId, jobPositionId)
                        .orderByDesc(JobMatchResult::getCreatedAt).orderByDesc(JobMatchResult::getId));
        Map<Long, JobPosition> jobs = jobsOf(result.getRecords());
        Map<Long, String> companies = companyNames(jobs.values());
        return PageResult.of(result.getRecords().stream()
                        .map(match -> toResponse(match, jobs.get(match.getJobPositionId()), companies))
                        .toList(),
                result.getTotal(), current, pageSize);
    }

    /** 发起或重跑匹配：立即返回 PROCESSING，前端轮询 GET /api/job-matches/{matchId}。 */
    public JobMatchResponse create(CreateJobMatchRequest request) {
        Resume resume = resumeMapper.selectById(request.resumeId());
        if (resume == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "简历不存在: " + request.resumeId());
        }
        JobPosition job = jobMapper.selectById(request.jobPositionId());
        if (job == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "岗位不存在: " + request.jobPositionId());
        }
        JobMatchResult existing = matchMapper.selectOne(Wrappers.<JobMatchResult>lambdaQuery()
                .eq(JobMatchResult::getResumeId, request.resumeId())
                .eq(JobMatchResult::getJobPositionId, request.jobPositionId()));
        if (existing != null && "PROCESSING".equals(existing.getStatus())) {
            throw BizException.of(ErrorCode.TASK_IN_PROGRESS);
        }
        JobMatchResult entity = existing;
        if (entity == null) {
            entity = new JobMatchResult();
            entity.setUserId(1L);
            entity.setResumeId(request.resumeId());
            entity.setJobPositionId(request.jobPositionId());
        }
        entity.setStatus("PROCESSING");
        entity.setError(null);
        entity.setRetryCount(0);
        entity.setOverallScore(null);
        entity.setDetail(null);
        entity.setLlmProvider(null);
        entity.setLlmModel(null);
        entity.setUpdatedAt(OffsetDateTime.now());
        if (existing == null) {
            matchMapper.insert(entity);
        } else {
            matchMapper.updateById(entity);
        }
        submit(entity.getId());
        return toResponse(matchMapper.selectById(entity.getId()), job, companyNames(List.of(job)));
    }

    public JobMatchResponse status(Long matchId) {
        JobMatchResult match = require(matchId);
        JobPosition job = jobMapper.selectById(match.getJobPositionId());
        return toResponse(match, job, job == null ? Map.of() : companyNames(List.of(job)));
    }

    private void submit(Long matchId) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        try {
            executor.execute(() -> {
                if (context != null) {
                    MDC.setContextMap(context);
                }
                try {
                    run(matchId);
                } finally {
                    MDC.clear();
                }
            });
        } catch (RuntimeException ex) {
            resetToPending(matchId, "匹配任务队列已满，请稍后重试");
            log.warn("匹配任务入队失败 matchId={}", matchId, ex);
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "匹配任务队列已满，请稍后重试");
        }
    }

    /** 异步 worker：首次 + 最多 3 次重试；分数越界或缺失会触发带错误反馈重试。 */
    private void run(Long matchId) {
        JobMatchResult match = matchMapper.selectById(matchId);
        if (match == null || !"PROCESSING".equals(match.getStatus())) {
            return;
        }
        Resume resume = resumeMapper.selectById(match.getResumeId());
        JobPosition job = jobMapper.selectById(match.getJobPositionId());
        if (resume == null || job == null) {
            finish(matchId, "FAILED", "简历或岗位已被删除", 0);
            return;
        }
        LlmSceneService.SceneModel target;
        LlmClient client;
        try {
            target = sceneService.resolve(LlmScene.JOB_MATCH);
            client = registry.get(target.providerKey());
        } catch (Exception ex) {
            finish(matchId, "FAILED", truncate(ex.getMessage()), 0);
            return;
        }
        StructuredOutputInvoker invoker = new StructuredOutputInvoker(client);
        List<ChatMessage> messages = List.of(
                ChatMessage.system(prompts.system()),
                ChatMessage.user(prompts.user(truncate(resume.getResumeText(), MAX_RESUME_CHARS),
                        jdText(job))));

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (matchMapper.selectById(matchId) == null) {
                log.info("匹配 {} 已删除，终止执行", matchId);
                return;
            }
            long start = System.nanoTime();
            try {
                StructuredOutputInvoker.Parsed<AiJobMatch> parsed =
                        invoker.invokeWithUsage(target.model(), messages, AiJobMatch.class);
                saveResult(matchId, parsed.value(), target, attempt);
                ChatResult usage = parsed.result();
                callLogger.log("JOB_MATCH", target.providerKey(), target.model(),
                        usage.promptTokens(), usage.completionTokens(), usage.totalTokens(),
                        elapsed(start), true, null);
                return;
            } catch (Exception ex) {
                if (matchMapper.selectById(matchId) == null) {
                    log.info("匹配 {} 已删除，终止执行", matchId);
                    return;
                }
                int retries = Math.min(attempt + 1, MAX_RETRIES);
                finish(matchId, attempt == MAX_RETRIES ? "FAILED" : "PROCESSING",
                        truncate(ex.getMessage()), retries);
                callLogger.log("JOB_MATCH", target.providerKey(), target.model(),
                        null, null, null, elapsed(start), false, ex.getMessage());
                log.warn("匹配失败 matchId={} 第 {} 次: {}", matchId, attempt + 1, ex.getMessage());
            }
        }
    }

    private void saveResult(Long matchId, AiJobMatch ai, LlmSceneService.SceneModel target, int attempt) {
        int skill = score(ai.skillMatch());
        int experience = score(ai.experienceMatch());
        int bonus = score(ai.bonusCoverage());
        int overall = (int) Math.round(skill * 0.5 + experience * 0.3 + bonus * 0.2);
        JobMatchDetailResponse detail = new JobMatchDetailResponse(
                List.of(new MatchDimensionResponse("skillMatch", "技能匹配", skill, 50),
                        new MatchDimensionResponse("experienceMatch", "经验匹配", experience, 30),
                        new MatchDimensionResponse("bonusCoverage", "加分项覆盖", bonus, 20)),
                strings(ai.gaps()), strings(ai.preparationAdvice()));
        JobMatchResult entity = matchMapper.selectById(matchId);
        if (entity == null) {
            return;
        }
        entity.setStatus("COMPLETED");
        entity.setError(null);
        entity.setRetryCount(attempt);
        entity.setOverallScore(overall);
        entity.setDetail(write(detail));
        entity.setLlmProvider(target.providerKey());
        entity.setLlmModel(target.model());
        entity.setUpdatedAt(OffsetDateTime.now());
        matchMapper.updateById(entity);
    }

    private void finish(Long matchId, String status, String error, int retryCount) {
        matchMapper.update(null, Wrappers.<JobMatchResult>lambdaUpdate()
                .eq(JobMatchResult::getId, matchId)
                .set(JobMatchResult::getStatus, status)
                .set(JobMatchResult::getError, truncate(error))
                .set(JobMatchResult::getRetryCount, retryCount)
                .set(JobMatchResult::getUpdatedAt, OffsetDateTime.now()));
    }

    private void resetToPending(Long matchId, String error) {
        matchMapper.update(null, Wrappers.<JobMatchResult>lambdaUpdate()
                .eq(JobMatchResult::getId, matchId)
                .eq(JobMatchResult::getStatus, "PROCESSING")
                .set(JobMatchResult::getStatus, "PENDING")
                .set(JobMatchResult::getError, truncate(error))
                .set(JobMatchResult::getUpdatedAt, OffsetDateTime.now()));
    }

    private JobMatchResult require(Long matchId) {
        JobMatchResult match = matchMapper.selectById(matchId);
        if (match == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "匹配记录不存在: " + matchId);
        }
        return match;
    }

    private JobMatchResponse toResponse(JobMatchResult match, JobPosition job, Map<Long, String> companies) {
        JobMatchDetailResponse detail = null;
        if ("COMPLETED".equals(match.getStatus()) && match.getDetail() != null) {
            try {
                detail = MAPPER.readValue(match.getDetail(), new TypeReference<JobMatchDetailResponse>() { });
            } catch (Exception ex) {
                log.warn("匹配详情解析失败 matchId={}", match.getId(), ex);
            }
        }
        Long companyId = job == null ? null : job.getCompanyId();
        return new JobMatchResponse(match.getId(), match.getResumeId(), match.getJobPositionId(),
                job == null ? null : job.getTitle(), companyId == null ? null : companies.get(companyId),
                match.getStatus(), match.getError(), match.getRetryCount(), match.getOverallScore(),
                detail, match.getLlmProvider(), match.getLlmModel(), match.getCreatedAt(), match.getUpdatedAt());
    }

    private Map<Long, JobPosition> jobsOf(List<JobMatchResult> matches) {
        List<Long> ids = matches.stream().map(JobMatchResult::getJobPositionId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return jobMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(JobPosition::getId, job -> job));
    }

    private Map<Long, String> companyNames(Iterable<JobPosition> jobs) {
        java.util.Set<Long> ids = new java.util.LinkedHashSet<>();
        jobs.forEach(job -> {
            if (job.getCompanyId() != null) {
                ids.add(job.getCompanyId());
            }
        });
        if (ids.isEmpty()) {
            return Map.of();
        }
        return companyMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(Company::getId, Company::getName));
    }

    /** 结构化 JD 与原文一起喂给模型，原文截断避免超出上下文。 */
    private static String jdText(JobPosition job) {
        StringBuilder builder = new StringBuilder();
        append(builder, "岗位名称", job.getTitle());
        append(builder, "城市", job.getCity());
        append(builder, "经验要求", job.getExperience());
        append(builder, "学历要求", job.getEducation());
        if (job.getSalaryMin() != null || job.getSalaryMax() != null) {
            builder.append("薪资：").append(job.getSalaryMin() == null ? "?" : job.getSalaryMin())
                    .append("-").append(job.getSalaryMax() == null ? "?" : job.getSalaryMax()).append(" 元/月\n");
        }
        append(builder, "必备技能", job.getMustHaveSkills());
        append(builder, "加分技能", job.getNiceToHaveSkills());
        append(builder, "职责摘要", job.getResponsibility());
        builder.append("\nJD 原文：\n").append(truncate(job.getRawText(), MAX_JD_CHARS));
        return builder.toString();
    }

    private static void append(StringBuilder builder, String label, String value) {
        if (value != null && !value.isBlank()) {
            builder.append(label).append("：").append(value).append('\n');
        }
    }

    private static int score(Integer value) {
        if (value == null) {
            throw new IllegalArgumentException("模型输出缺少评分字段");
        }
        return Math.max(0, Math.min(100, value));
    }

    /** 文字清单兜底：去空、截断，最多 10 条。 */
    private static List<String> strings(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().length() > 200 ? value.trim().substring(0, 200) : value.trim())
                .limit(10).toList();
    }

    private static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String truncate(String text) {
        return truncate(text, 500);
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
