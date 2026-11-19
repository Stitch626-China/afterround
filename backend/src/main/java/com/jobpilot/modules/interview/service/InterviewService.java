package com.jobpilot.modules.interview.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
import com.jobpilot.modules.chat.dto.SwitchModelRequest;
import com.jobpilot.modules.company.entity.Company;
import com.jobpilot.modules.company.mapper.CompanyMapper;
import com.jobpilot.modules.interview.ai.AiInterviewEvaluation;
import com.jobpilot.modules.interview.ai.AiInterviewQuestions;
import com.jobpilot.modules.interview.dto.AnswerInterviewQuestionRequest;
import com.jobpilot.modules.interview.dto.CreateInterviewSessionRequest;
import com.jobpilot.modules.interview.dto.InterviewDirection;
import com.jobpilot.modules.interview.dto.InterviewEvaluation;
import com.jobpilot.modules.interview.dto.InterviewEvaluationCategory;
import com.jobpilot.modules.interview.dto.InterviewQuestion;
import com.jobpilot.modules.interview.dto.InterviewSessionDetail;
import com.jobpilot.modules.interview.entity.InterviewSession;
import com.jobpilot.modules.interview.mapper.InterviewQuestionMapper;
import com.jobpilot.modules.interview.mapper.InterviewSessionMapper;
import com.jobpilot.modules.interview.prompt.InterviewPromptFactory;
import com.jobpilot.modules.interview.service.InterviewDirectionService.Direction;
import com.jobpilot.modules.job.entity.JobPosition;
import com.jobpilot.modules.job.mapper.JobPositionMapper;
import com.jobpilot.modules.llm.dto.LlmScene;
import com.jobpilot.modules.llm.entity.LlmModel;
import com.jobpilot.modules.llm.entity.LlmProvider;
import com.jobpilot.modules.llm.mapper.LlmModelMapper;
import com.jobpilot.modules.llm.mapper.LlmProviderMapper;
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
 * 模拟面试（FR3）：出题 → 逐题作答 / 跳过 → 追问 → 交卷 → 统一评估。
 *
 * <p>状态机：PREPARING（首次出题）→ IN_PROGRESS（作答）→ GENERATING（追问生成）→
 * SUBMITTED（评估中）→ COMPLETED；出题重试耗尽置 FAILED。评估状态独立走四件套。
 */
@Service
public class InterviewService {

    private static final Logger log = LoggerFactory.getLogger(InterviewService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final int MAX_RETRIES = 3;
    private static final int SHIFT_OFFSET = 1_000_000;
    private static final int MAX_RESUME_CHARS = 20_000;
    private static final int MAX_JD_CHARS = 8_000;
    private static final int MAX_HISTORY_SESSIONS = 10;
    private static final int MAX_HISTORY_TOPICS = 30;
    private static final Set<String> RUNNING = Set.of("PREPARING", "GENERATING", "SUBMITTED");

    private final InterviewSessionMapper sessionMapper;
    private final InterviewQuestionMapper questionMapper;
    private final ResumeMapper resumeMapper;
    private final JobPositionMapper jobMapper;
    private final CompanyMapper companyMapper;
    private final LlmProviderMapper providerMapper;
    private final LlmModelMapper modelMapper;
    private final InterviewDirectionService directionService;
    private final InterviewPromptFactory prompts;
    private final LlmSceneService sceneService;
    private final ProviderClientRegistry registry;
    private final LlmCallLogger callLogger;
    private final ThreadPoolTaskExecutor executor;

    public InterviewService(InterviewSessionMapper sessionMapper, InterviewQuestionMapper questionMapper,
                            ResumeMapper resumeMapper, JobPositionMapper jobMapper, CompanyMapper companyMapper,
                            LlmProviderMapper providerMapper, LlmModelMapper modelMapper,
                            InterviewDirectionService directionService, InterviewPromptFactory prompts,
                            LlmSceneService sceneService, ProviderClientRegistry registry,
                            LlmCallLogger callLogger,
                            @Qualifier("jobpilotTaskExecutor") ThreadPoolTaskExecutor executor) {
        this.sessionMapper = sessionMapper;
        this.questionMapper = questionMapper;
        this.resumeMapper = resumeMapper;
        this.jobMapper = jobMapper;
        this.companyMapper = companyMapper;
        this.providerMapper = providerMapper;
        this.modelMapper = modelMapper;
        this.directionService = directionService;
        this.prompts = prompts;
        this.sceneService = sceneService;
        this.registry = registry;
        this.callLogger = callLogger;
        this.executor = executor;
    }

    /** 可选方向：由 classpath 下 skill.meta.yml 定义。 */
    public List<InterviewDirection> directions() {
        return directionService.list().stream()
                .map(direction -> new InterviewDirection(direction.key(), direction.label(), direction.description()))
                .toList();
    }

    public PageResult<com.jobpilot.modules.interview.dto.InterviewSession> page(Integer page, Integer size,
                                                                              String direction, String status,
                                             OffsetDateTime createdFrom, OffsetDateTime createdTo) {
        int current = PageResult.normalizePage(page);
        int pageSize = PageResult.normalizeSize(size);
        Page<InterviewSession> result = sessionMapper.selectPage(new Page<>(current, pageSize),
                Wrappers.<InterviewSession>lambdaQuery()
                        .eq(!blank(direction), InterviewSession::getDirection, direction)
                        .eq(!blank(status), InterviewSession::getStatus, status)
                        .ge(createdFrom != null, InterviewSession::getCreatedAt, createdFrom)
                        .le(createdTo != null, InterviewSession::getCreatedAt, createdTo)
                        .orderByDesc(InterviewSession::getCreatedAt).orderByDesc(InterviewSession::getId));
        Map<Long, Refs> refs = refs(result.getRecords());
        return PageResult.of(result.getRecords().stream()
                .map(session -> toSession(session, refs.get(session.getId())))
                .toList(), result.getTotal(), current, pageSize);
    }

    /** 创建会话并异步出题：立即返回 PREPARING，前端轮询详情直到 IN_PROGRESS / FAILED。 */
    public com.jobpilot.modules.interview.dto.InterviewSession create(CreateInterviewSessionRequest request) {
        JobPosition job = null;
        if (request.jobPositionId() != null) {
            job = jobMapper.selectById(request.jobPositionId());
            if (job == null) {
                throw BizException.of(ErrorCode.NOT_FOUND, "岗位不存在: " + request.jobPositionId());
            }
        }
        if (request.resumeId() != null && resumeMapper.selectById(request.resumeId()) == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "简历不存在: " + request.resumeId());
        }
        Direction direction;
        if (!blank(request.direction())) {
            direction = directionService.require(request.direction());
        } else if (job != null) {
            direction = directionService.match(jdCorpus(job));
        } else {
            throw BizException.of(ErrorCode.BAD_REQUEST, "direction 与 jobPositionId 至少填写一个");
        }
        LlmSceneService.SceneModel target = resolveTarget(request.providerKey(), request.model());

        InterviewSession entity = new InterviewSession();
        entity.setUserId(1L);
        entity.setDirection(direction.key());
        entity.setDirectionLabel(direction.label());
        entity.setResumeId(request.resumeId());
        entity.setJobPositionId(request.jobPositionId());
        entity.setStatus("PREPARING");
        entity.setQuestionCount(request.questionCount() == null ? 8 : request.questionCount());
        entity.setFollowUpCount(request.followUpCount() == null ? 1 : request.followUpCount());
        entity.setAnsweredCount(0);
        entity.setCurrentProviderKey(target.providerKey());
        entity.setCurrentModel(target.model());
        entity.setRetryCount(0);
        entity.setEvaluateStatus("PENDING");
        entity.setEvaluateRetryCount(0);
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setUpdatedAt(OffsetDateTime.now());
        sessionMapper.insert(entity);
        if (!submit(() -> runQuestionGeneration(entity.getId()))) {
            finishQuestions(entity.getId(), "FAILED", "出题任务队列已满，请稍后重试", 0);
            entity.setStatus("FAILED");
            entity.setError("出题任务队列已满，请稍后重试");
        }
        return toSession(entity, refs(entity.getResumeId(), entity.getJobPositionId()));
    }

    public InterviewSessionDetail detail(Long sessionId) {
        InterviewSession session = requireSession(sessionId);
        return toDetail(session, refs(session.getResumeId(), session.getJobPositionId()), questions(sessionId));
    }

    public void delete(Long sessionId) {
        requireSession(sessionId);
        sessionMapper.deleteById(sessionId);
    }

    /** 两级切换：仅 IN_PROGRESS / FAILED 可切，执行中 40901、终态 40902（FR3.7 / FRX.6）。 */
    public com.jobpilot.modules.interview.dto.InterviewSession switchModel(Long sessionId,
                                                                          SwitchModelRequest request) {
        InterviewSession session = requireSession(sessionId);
        requireSwitchable(session.getStatus());
        requireEnabledModel(request.providerKey(), request.model());
        int updated = sessionMapper.update(null, Wrappers.<InterviewSession>lambdaUpdate()
                .eq(InterviewSession::getId, sessionId)
                .in(InterviewSession::getStatus, "IN_PROGRESS", "FAILED")
                .set(InterviewSession::getCurrentProviderKey, request.providerKey())
                .set(InterviewSession::getCurrentModel, request.model())
                .set(InterviewSession::getUpdatedAt, OffsetDateTime.now()));
        if (updated == 0) {
            requireSwitchable(requireSession(sessionId).getStatus());
            throw BizException.of(ErrorCode.INTERVIEW_STATE_INVALID);
        }
        InterviewSession saved = requireSession(sessionId);
        return toSession(saved, refs(saved.getResumeId(), saved.getJobPositionId()));
    }

    /** 出题失败后重试：仅 FAILED 可调用，清空残留题目并重置 retryCount。 */
    public com.jobpilot.modules.interview.dto.InterviewSession retryQuestions(Long sessionId) {
        InterviewSession session = requireSession(sessionId);
        if (!"FAILED".equals(session.getStatus())) {
            throw BizException.of(ErrorCode.INTERVIEW_STATE_INVALID);
        }
        int updated = sessionMapper.update(null, Wrappers.<InterviewSession>lambdaUpdate()
                .eq(InterviewSession::getId, sessionId)
                .eq(InterviewSession::getStatus, "FAILED")
                .set(InterviewSession::getStatus, "PREPARING")
                .set(InterviewSession::getRetryCount, 0)
                .set(InterviewSession::getError, null)
                .set(InterviewSession::getAnsweredCount, 0)
                .set(InterviewSession::getUpdatedAt, OffsetDateTime.now()));
        if (updated == 0) {
            throw BizException.of(ErrorCode.INTERVIEW_STATE_INVALID);
        }
        questionMapper.delete(Wrappers.<com.jobpilot.modules.interview.entity.InterviewQuestion>lambdaQuery()
                .eq(com.jobpilot.modules.interview.entity.InterviewQuestion::getSessionId, sessionId));
        if (!submit(() -> runQuestionGeneration(sessionId))) {
            finishQuestions(sessionId, "FAILED", "出题任务队列已满，请稍后重试", 0);
        }
        return toSession(requireSession(sessionId), refs(session.getResumeId(), session.getJobPositionId()));
    }

    /** 单题作答：主问题作答后异步生成整组追问，链上题目顺延（FR3.4）。 */
    public InterviewQuestion answer(Long sessionId, Integer questionIndex, AnswerInterviewQuestionRequest request) {
        InterviewSession session = requireSession(sessionId);
        requireAnswerable(session.getStatus());
        var question = requireQuestion(sessionId, questionIndex);
        int updated = questionMapper.update(null, Wrappers
                .<com.jobpilot.modules.interview.entity.InterviewQuestion>lambdaUpdate()
                .eq(com.jobpilot.modules.interview.entity.InterviewQuestion::getId, question.getId())
                .eq(com.jobpilot.modules.interview.entity.InterviewQuestion::getAnswerStatus, "UNANSWERED")
                .set(com.jobpilot.modules.interview.entity.InterviewQuestion::getAnswerStatus, "ANSWERED")
                .set(com.jobpilot.modules.interview.entity.InterviewQuestion::getAnswer, request.answer().trim())
                .set(com.jobpilot.modules.interview.entity.InterviewQuestion::getUpdatedAt, OffsetDateTime.now()));
        if (updated == 0) {
            throw BizException.of(ErrorCode.INTERVIEW_STATE_INVALID);
        }
        bumpAnswered(sessionId);
        var saved = questionMapper.selectById(question.getId());
        int followUps = session.getFollowUpCount() == null ? 0 : session.getFollowUpCount();
        if ("MAIN".equals(saved.getType()) && followUps > 0 && startFollowUp(sessionId)
                && !submit(() -> runFollowUpGeneration(sessionId, saved.getQuestionIndex()))) {
            finishFollowUp(sessionId, "追问任务队列已满，可继续作答或稍后重试", 0);
        }
        return toQuestion(saved);
    }

    public InterviewQuestion skip(Long sessionId, Integer questionIndex) {
        InterviewSession session = requireSession(sessionId);
        requireAnswerable(session.getStatus());
        var question = requireQuestion(sessionId, questionIndex);
        int updated = questionMapper.update(null, Wrappers
                .<com.jobpilot.modules.interview.entity.InterviewQuestion>lambdaUpdate()
                .eq(com.jobpilot.modules.interview.entity.InterviewQuestion::getId, question.getId())
                .eq(com.jobpilot.modules.interview.entity.InterviewQuestion::getAnswerStatus, "UNANSWERED")
                .set(com.jobpilot.modules.interview.entity.InterviewQuestion::getAnswerStatus, "SKIPPED")
                .set(com.jobpilot.modules.interview.entity.InterviewQuestion::getUpdatedAt, OffsetDateTime.now()));
        if (updated == 0) {
            throw BizException.of(ErrorCode.INTERVIEW_STATE_INVALID);
        }
        bumpAnswered(sessionId);
        return toQuestion(questionMapper.selectById(question.getId()));
    }

    /** 交卷：锁定作答并异步评估；未作答 / 跳过题在评估时记 0 分（FR3.5 / FR3.6）。 */
    public com.jobpilot.modules.interview.dto.InterviewSession submit(Long sessionId) {
        InterviewSession session = requireSession(sessionId);
        if ("PREPARING".equals(session.getStatus()) || "GENERATING".equals(session.getStatus())) {
            throw BizException.of(ErrorCode.INTERVIEW_TASK_IN_PROGRESS);
        }
        if ("SUBMITTED".equals(session.getStatus()) || "COMPLETED".equals(session.getStatus())) {
            throw BizException.of(ErrorCode.INTERVIEW_FINISHED);
        }
        if (!"IN_PROGRESS".equals(session.getStatus())) {
            throw BizException.of(ErrorCode.INTERVIEW_STATE_INVALID);
        }
        Long count = questionMapper.selectCount(Wrappers
                .<com.jobpilot.modules.interview.entity.InterviewQuestion>lambdaQuery()
                .eq(com.jobpilot.modules.interview.entity.InterviewQuestion::getSessionId, sessionId));
        if (count == null || count == 0) {
            throw BizException.of(ErrorCode.INTERVIEW_STATE_INVALID, "会话没有题目，无法交卷");
        }
        int updated = sessionMapper.update(null, Wrappers.<InterviewSession>lambdaUpdate()
                .eq(InterviewSession::getId, sessionId)
                .eq(InterviewSession::getStatus, "IN_PROGRESS")
                .set(InterviewSession::getStatus, "SUBMITTED")
                .set(InterviewSession::getEvaluateStatus, "PROCESSING")
                .set(InterviewSession::getEvaluateError, null)
                .set(InterviewSession::getEvaluateRetryCount, 0)
                .set(InterviewSession::getUpdatedAt, OffsetDateTime.now()));
        if (updated == 0) {
            throw submitGuard(requireSession(sessionId).getStatus());
        }
        if (!submit(() -> runEvaluation(sessionId))) {
            // 队列满回退为可交卷状态，避免会话永久停在 SUBMITTED 且无评估
            sessionMapper.update(null, Wrappers.<InterviewSession>lambdaUpdate()
                    .eq(InterviewSession::getId, sessionId)
                    .set(InterviewSession::getStatus, "IN_PROGRESS")
                    .set(InterviewSession::getEvaluateStatus, "PENDING")
                    .set(InterviewSession::getUpdatedAt, OffsetDateTime.now()));
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "评估任务队列已满，请稍后重新交卷");
        }
        InterviewSession saved = requireSession(sessionId);
        return toSession(saved, refs(saved.getResumeId(), saved.getJobPositionId()));
    }

    public InterviewEvaluation evaluation(Long sessionId) {
        InterviewSession session = requireSession(sessionId);
        if (!"SUBMITTED".equals(session.getStatus()) && !"COMPLETED".equals(session.getStatus())) {
            throw BizException.of(ErrorCode.INTERVIEW_STATE_INVALID, "会话未交卷，尚无评估");
        }
        return new InterviewEvaluation(session.getEvaluateStatus(), session.getEvaluateError(),
                session.getEvaluateRetryCount() == null ? 0 : session.getEvaluateRetryCount(),
                session.getEvaluateOverallScore(), categories(session.getEvaluateCategories()),
                strings(session.getEvaluateStrengths()), strings(session.getEvaluateImprovements()),
                session.getEvaluateSummary(), session.getEvaluateProvider(), session.getEvaluateModel(),
                session.getEvaluatedAt());
    }

    // ---------- 出题 ----------

    /** 异步出题 worker：首次 + 最多 3 次重试；失败置 FAILED 并保留错误供重试。 */
    private void runQuestionGeneration(Long sessionId) {
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null || !"PREPARING".equals(session.getStatus())) {
            return;
        }
        Direction direction;
        LlmClient client;
        try {
            direction = directionService.require(session.getDirection());
            client = registry.get(session.getCurrentProviderKey());
        } catch (Exception ex) {
            finishQuestions(sessionId, "FAILED", ex.getMessage(), 0);
            return;
        }
        Resume resume = session.getResumeId() == null ? null : resumeMapper.selectById(session.getResumeId());
        JobPosition job = session.getJobPositionId() == null ? null : jobMapper.selectById(session.getJobPositionId());
        List<ChatMessage> messages = List.of(
                ChatMessage.system(prompts.questionSystem(direction, session.getQuestionCount(),
                        session.getFollowUpCount(), resume != null)),
                ChatMessage.user(prompts.questionUser(
                        resume == null ? null : truncate(resume.getResumeText(), MAX_RESUME_CHARS),
                        job == null ? null : jdCorpus(job),
                        historyTopics(session.getDirection(), sessionId))));
        StructuredOutputInvoker invoker = new StructuredOutputInvoker(client);
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (sessionMapper.selectById(sessionId) == null) {
                log.info("面试会话 {} 已删除，终止出题", sessionId);
                return;
            }
            long start = System.nanoTime();
            try {
                StructuredOutputInvoker.Parsed<AiInterviewQuestions> parsed = invoker.invokeWithUsage(
                        session.getCurrentModel(), messages, AiInterviewQuestions.class);
                saveQuestions(sessionId, parsed.value(), session.getCurrentProviderKey(),
                        session.getCurrentModel(), attempt);
                ChatResult usage = parsed.result();
                callLogger.log("INTERVIEW_QUESTION", session.getCurrentProviderKey(), session.getCurrentModel(),
                        usage.promptTokens(), usage.completionTokens(), usage.totalTokens(),
                        elapsed(start), true, null);
                return;
            } catch (Exception ex) {
                if (sessionMapper.selectById(sessionId) == null) {
                    log.info("面试会话 {} 已删除，终止出题", sessionId);
                    return;
                }
                int retries = Math.min(attempt + 1, MAX_RETRIES);
                finishQuestions(sessionId, attempt == MAX_RETRIES ? "FAILED" : "PREPARING",
                        truncate(ex.getMessage()), retries);
                callLogger.log("INTERVIEW_QUESTION", session.getCurrentProviderKey(), session.getCurrentModel(),
                        null, null, null, elapsed(start), false, ex.getMessage());
                log.warn("面试出题失败 sessionId={} 第 {} 次: {}", sessionId, attempt + 1, ex.getMessage());
            }
        }
    }

    /** 本地兜底：数量、空题、难度、要点与参考答案统一清洗后再落库。 */
    private void saveQuestions(Long sessionId, AiInterviewQuestions ai, String providerKey, String model,
                               int attempt) {
        List<AiInterviewQuestions.Item> items = ai == null || ai.questions() == null ? List.of() : ai.questions();
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            return;
        }
        int required = session.getQuestionCount() == null ? 8 : session.getQuestionCount();
        if (items.size() < required) {
            throw new IllegalArgumentException("模型返回题目数量不足：需要 " + required + " 道，实际 " + items.size() + " 道");
        }
        OffsetDateTime now = OffsetDateTime.now();
        // 重试前可能残留半成品题目，先清空再插入，保证重试幂等
        questionMapper.delete(Wrappers.<com.jobpilot.modules.interview.entity.InterviewQuestion>lambdaQuery()
                .eq(com.jobpilot.modules.interview.entity.InterviewQuestion::getSessionId, sessionId));
        for (int index = 0; index < required; index++) {
            AiInterviewQuestions.Item item = items.get(index);
            if (item == null || blank(item.content())) {
                throw new IllegalArgumentException("第 " + (index + 1) + " 道题内容为空");
            }
            var entity = new com.jobpilot.modules.interview.entity.InterviewQuestion();
            entity.setSessionId(sessionId);
            entity.setQuestionIndex(index + 1);
            entity.setType("MAIN");
            entity.setContent(truncate(item.content().trim(), 4000));
            entity.setTopicSummary(truncate(blank(item.topicSummary())
                    ? summarize(item.content()) : item.topicSummary().trim(), 255));
            entity.setDifficulty(difficulty(item.difficulty()));
            entity.setAnswerStatus("UNANSWERED");
            entity.setKeyPoints(write(strings(item.keyPoints())));
            entity.setReferenceAnswer(truncate(blank(item.referenceAnswer()) ? null : item.referenceAnswer().trim(), 2000));
            entity.setLlmProvider(providerKey);
            entity.setLlmModel(model);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            questionMapper.insert(entity);
        }
        sessionMapper.update(null, Wrappers.<InterviewSession>lambdaUpdate()
                .eq(InterviewSession::getId, sessionId)
                .set(InterviewSession::getStatus, "IN_PROGRESS")
                .set(InterviewSession::getError, null)
                .set(InterviewSession::getRetryCount, attempt)
                .set(InterviewSession::getAnsweredCount, 0)
                .set(InterviewSession::getUpdatedAt, now));
    }

    // ---------- 追问 ----------

    /** 主问题作答后把会话置为 GENERATING；返回 false 表示已被交卷 / 删除，跳过追问。 */
    private boolean startFollowUp(Long sessionId) {
        return sessionMapper.update(null, Wrappers.<InterviewSession>lambdaUpdate()
                .eq(InterviewSession::getId, sessionId)
                .eq(InterviewSession::getStatus, "IN_PROGRESS")
                .set(InterviewSession::getStatus, "GENERATING")
                .set(InterviewSession::getUpdatedAt, OffsetDateTime.now())) > 0;
    }

    /** 追问 worker：失败只回退 IN_PROGRESS，绝不丢弃已有作答。 */
    private void runFollowUpGeneration(Long sessionId, Integer mainIndex) {
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null || !"GENERATING".equals(session.getStatus())) {
            return;
        }
        var main = questionMapper.selectOne(Wrappers
                .<com.jobpilot.modules.interview.entity.InterviewQuestion>lambdaQuery()
                .eq(com.jobpilot.modules.interview.entity.InterviewQuestion::getSessionId, sessionId)
                .eq(com.jobpilot.modules.interview.entity.InterviewQuestion::getQuestionIndex, mainIndex));
        if (main == null) {
            finishFollowUp(sessionId, "主问题已不存在，已跳过追问", 0);
            return;
        }
        int count = session.getFollowUpCount() == null ? 1 : session.getFollowUpCount();
        Direction direction;
        LlmClient client;
        try {
            direction = directionService.require(session.getDirection());
            client = registry.get(session.getCurrentProviderKey());
        } catch (Exception ex) {
            finishFollowUp(sessionId, ex.getMessage(), 0);
            return;
        }
        List<ChatMessage> messages = List.of(
                ChatMessage.system(prompts.followUpSystem(direction, count)),
                ChatMessage.user(prompts.followUpUser(session.getDirectionLabel(), main.getContent(), main.getAnswer())));
        StructuredOutputInvoker invoker = new StructuredOutputInvoker(client);
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (sessionMapper.selectById(sessionId) == null) {
                return;
            }
            long start = System.nanoTime();
            try {
                StructuredOutputInvoker.Parsed<AiInterviewQuestions> parsed = invoker.invokeWithUsage(
                        session.getCurrentModel(), messages, AiInterviewQuestions.class);
                saveFollowUps(sessionId, mainIndex, parsed.value(), count,
                        session.getCurrentProviderKey(), session.getCurrentModel(), attempt);
                ChatResult usage = parsed.result();
                callLogger.log("INTERVIEW_QUESTION", session.getCurrentProviderKey(), session.getCurrentModel(),
                        usage.promptTokens(), usage.completionTokens(), usage.totalTokens(),
                        elapsed(start), true, null);
                return;
            } catch (Exception ex) {
                if (sessionMapper.selectById(sessionId) == null) {
                    return;
                }
                finishFollowUp(sessionId, truncate(ex.getMessage()), Math.min(attempt + 1, MAX_RETRIES));
                callLogger.log("INTERVIEW_QUESTION", session.getCurrentProviderKey(), session.getCurrentModel(),
                        null, null, null, elapsed(start), false, ex.getMessage());
                log.warn("追问生成失败 sessionId={} 第 {} 次: {}", sessionId, attempt + 1, ex.getMessage());
            }
        }
    }

    /** 追问插入主问题之后：后续题目整体后移，parent_index 同步平移，保证顺序与链关系。 */
    private void saveFollowUps(Long sessionId, Integer mainIndex, AiInterviewQuestions ai, int count,
                               String providerKey, String model, int attempt) {
        List<AiInterviewQuestions.Item> items = ai == null || ai.questions() == null ? List.of() : ai.questions();
        if (items.size() < count) {
            throw new IllegalArgumentException("追问数量不足：需要 " + count + " 条，实际 " + items.size() + " 条");
        }
        shiftAfter(sessionId, mainIndex, count);
        OffsetDateTime now = OffsetDateTime.now();
        for (int index = 0; index < count; index++) {
            AiInterviewQuestions.Item item = items.get(index);
            if (item == null || blank(item.content())) {
                throw new IllegalArgumentException("第 " + (index + 1) + " 条追问内容为空");
            }
            var entity = new com.jobpilot.modules.interview.entity.InterviewQuestion();
            entity.setSessionId(sessionId);
            entity.setQuestionIndex(mainIndex + index + 1);
            entity.setParentIndex(mainIndex);
            entity.setType("FOLLOW_UP");
            entity.setContent(truncate(item.content().trim(), 4000));
            entity.setTopicSummary(truncate(blank(item.topicSummary())
                    ? summarize(item.content()) : item.topicSummary().trim(), 255));
            entity.setDifficulty(difficulty(item.difficulty()));
            entity.setAnswerStatus("UNANSWERED");
            entity.setKeyPoints(write(strings(item.keyPoints())));
            entity.setReferenceAnswer(truncate(blank(item.referenceAnswer()) ? null : item.referenceAnswer().trim(), 2000));
            entity.setLlmProvider(providerKey);
            entity.setLlmModel(model);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            questionMapper.insert(entity);
        }
        sessionMapper.update(null, Wrappers.<InterviewSession>lambdaUpdate()
                .eq(InterviewSession::getId, sessionId)
                .eq(InterviewSession::getStatus, "GENERATING")
                .set(InterviewSession::getStatus, "IN_PROGRESS")
                .set(InterviewSession::getError, null)
                .set(InterviewSession::getRetryCount, attempt)
                .set(InterviewSession::getUpdatedAt, now));
    }

    /** 两段式平移：先加偏移量避开唯一约束，再落到最终位置。 */
    private void shiftAfter(Long sessionId, int afterIndex, int offset) {
        questionMapper.update(null, Wrappers
                .<com.jobpilot.modules.interview.entity.InterviewQuestion>lambdaUpdate()
                .eq(com.jobpilot.modules.interview.entity.InterviewQuestion::getSessionId, sessionId)
                .gt(com.jobpilot.modules.interview.entity.InterviewQuestion::getQuestionIndex, afterIndex)
                .setSql("question_index = question_index + " + SHIFT_OFFSET));
        questionMapper.update(null, Wrappers
                .<com.jobpilot.modules.interview.entity.InterviewQuestion>lambdaUpdate()
                .eq(com.jobpilot.modules.interview.entity.InterviewQuestion::getSessionId, sessionId)
                .gt(com.jobpilot.modules.interview.entity.InterviewQuestion::getQuestionIndex,
                        afterIndex + SHIFT_OFFSET)
                .setSql("question_index = question_index - " + SHIFT_OFFSET + " + " + offset));
        questionMapper.update(null, Wrappers
                .<com.jobpilot.modules.interview.entity.InterviewQuestion>lambdaUpdate()
                .eq(com.jobpilot.modules.interview.entity.InterviewQuestion::getSessionId, sessionId)
                .gt(com.jobpilot.modules.interview.entity.InterviewQuestion::getParentIndex, afterIndex)
                .setSql("parent_index = parent_index + " + offset));
    }

    // ---------- 评估 ----------

    /** 评估 worker：场景显式绑定优先，其次会话当前模型（支持会话内切换），最后全局默认。 */
    private void runEvaluation(Long sessionId) {
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null || !"SUBMITTED".equals(session.getStatus())
                || !"PROCESSING".equals(session.getEvaluateStatus())) {
            return;
        }
        List<com.jobpilot.modules.interview.entity.InterviewQuestion> questions = questions(sessionId);
        Direction direction;
        LlmSceneService.SceneModel target;
        LlmClient client;
        try {
            direction = directionService.require(session.getDirection());
            target = evaluationTarget(session);
            client = registry.get(target.providerKey());
        } catch (Exception ex) {
            finishEvaluation(sessionId, ex.getMessage(), 0);
            return;
        }
        List<ChatMessage> messages = List.of(
                ChatMessage.system(prompts.evaluateSystem(direction)),
                ChatMessage.user(prompts.evaluateUser(session.getDirectionLabel(), transcript(questions))));
        StructuredOutputInvoker invoker = new StructuredOutputInvoker(client);
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (sessionMapper.selectById(sessionId) == null) {
                return;
            }
            long start = System.nanoTime();
            try {
                StructuredOutputInvoker.Parsed<AiInterviewEvaluation> parsed = invoker.invokeWithUsage(
                        target.model(), messages, AiInterviewEvaluation.class);
                saveEvaluation(sessionId, direction, questions, parsed.value(), target, attempt);
                ChatResult usage = parsed.result();
                callLogger.log("INTERVIEW_EVALUATE", target.providerKey(), target.model(),
                        usage.promptTokens(), usage.completionTokens(), usage.totalTokens(),
                        elapsed(start), true, null);
                return;
            } catch (Exception ex) {
                if (sessionMapper.selectById(sessionId) == null) {
                    return;
                }
                finishEvaluation(sessionId, truncate(ex.getMessage()), Math.min(attempt + 1, MAX_RETRIES));
                callLogger.log("INTERVIEW_EVALUATE", target.providerKey(), target.model(),
                        null, null, null, elapsed(start), false, ex.getMessage());
                log.warn("面试评估失败 sessionId={} 第 {} 次: {}", sessionId, attempt + 1, ex.getMessage());
            }
        }
    }

    private LlmSceneService.SceneModel evaluationTarget(InterviewSession session) {
        LlmSceneService.SceneModel bound = sceneService.optionalResolve(LlmScene.INTERVIEW_EVALUATE);
        if (bound != null) {
            return bound;
        }
        if (!blank(session.getCurrentProviderKey()) && !blank(session.getCurrentModel())) {
            return new LlmSceneService.SceneModel(session.getCurrentProviderKey(), session.getCurrentModel());
        }
        return sceneService.resolveGlobal();
    }

    /**
     * 本地兜底评分：跳过 / 未作答强制 0 分且不生成点评；分类缺失按已答题均分补齐；
     * 总分按 skill.meta.yml 权重本地加权（FR3.5 / FR3.6）。
     */
    private void saveEvaluation(Long sessionId, Direction direction,
                                List<com.jobpilot.modules.interview.entity.InterviewQuestion> questions,
                                AiInterviewEvaluation ai, LlmSceneService.SceneModel target, int attempt) {
        Map<Integer, AiInterviewEvaluation.QuestionScore> scored = new HashMap<>();
        if (ai != null && ai.questions() != null) {
            for (AiInterviewEvaluation.QuestionScore item : ai.questions()) {
                if (item != null && item.questionIndex() != null) {
                    scored.put(item.questionIndex(), item);
                }
            }
        }
        OffsetDateTime now = OffsetDateTime.now();
        int answeredSum = 0;
        int answeredCount = 0;
        for (var question : questions) {
            boolean answered = "ANSWERED".equals(question.getAnswerStatus());
            Integer score = 0;
            String feedback = null;
            String referenceAnswer = null;
            if (answered) {
                AiInterviewEvaluation.QuestionScore item = scored.get(question.getQuestionIndex());
                if (item == null || item.score() == null) {
                    throw new IllegalArgumentException("缺少第 " + question.getQuestionIndex() + " 题评分");
                }
                score = clamp(item.score());
                feedback = truncate(blank(item.feedback()) ? null : item.feedback().trim(), 1000);
                referenceAnswer = truncate(blank(item.referenceAnswer())
                        ? question.getReferenceAnswer() : item.referenceAnswer().trim(), 2000);
                answeredSum += score;
                answeredCount++;
            }
            questionMapper.update(null, Wrappers
                    .<com.jobpilot.modules.interview.entity.InterviewQuestion>lambdaUpdate()
                    .eq(com.jobpilot.modules.interview.entity.InterviewQuestion::getId, question.getId())
                    .set(com.jobpilot.modules.interview.entity.InterviewQuestion::getScore, score)
                    .set(com.jobpilot.modules.interview.entity.InterviewQuestion::getFeedback, feedback)
                    .set(com.jobpilot.modules.interview.entity.InterviewQuestion::getReferenceAnswer, referenceAnswer)
                    .set(com.jobpilot.modules.interview.entity.InterviewQuestion::getUpdatedAt, now));
        }
        Map<String, Integer> aiCategories = new HashMap<>();
        if (ai != null && ai.categories() != null) {
            ai.categories().stream().filter(Objects::nonNull).filter(item -> item.key() != null)
                    .forEach(item -> aiCategories.put(item.key(), item.score()));
        }
        int average = answeredCount == 0 ? 0 : Math.round((float) answeredSum / answeredCount);
        List<InterviewEvaluationCategory> categories = new ArrayList<>();
        int weighted = 0;
        int weights = 0;
        for (InterviewDirectionService.Category category : direction.categories()) {
            Integer score = aiCategories.get(category.key());
            int value = clamp(score == null ? average : score);
            categories.add(new InterviewEvaluationCategory(category.key(), category.label(), value));
            weighted += value * category.weight();
            weights += category.weight();
        }
        int overall = weights == 0 ? average : Math.round((float) weighted / weights);
        sessionMapper.update(null, Wrappers.<InterviewSession>lambdaUpdate()
                .eq(InterviewSession::getId, sessionId)
                .set(InterviewSession::getStatus, "COMPLETED")
                .set(InterviewSession::getEvaluateStatus, "COMPLETED")
                .set(InterviewSession::getEvaluateOverallScore, overall)
                .setSql("evaluate_categories = {0}::jsonb", write(categories))
                .setSql("evaluate_strengths = {0}::jsonb",
                        write(strings(ai == null ? null : ai.strengths())))
                .setSql("evaluate_improvements = {0}::jsonb",
                        write(strings(ai == null ? null : ai.improvements())))
                .set(InterviewSession::getEvaluateSummary,
                        truncate(blank(ai == null ? null : ai.summary()) ? null : ai.summary().trim(), 2000))
                .set(InterviewSession::getEvaluateProvider, target.providerKey())
                .set(InterviewSession::getEvaluateModel, target.model())
                .set(InterviewSession::getEvaluateError, null)
                .set(InterviewSession::getEvaluateRetryCount, attempt)
                .set(InterviewSession::getEvaluatedAt, now)
                .set(InterviewSession::getUpdatedAt, now));
    }

    /** 评估输入只带题干、状态与答案；跳过 / 未作答明确标注，避免模型虚构作答。 */
    private static String transcript(List<com.jobpilot.modules.interview.entity.InterviewQuestion> questions) {
        StringBuilder builder = new StringBuilder();
        for (var question : questions) {
            builder.append('[').append(question.getQuestionIndex()).append("] 类型=")
                    .append("MAIN".equals(question.getType()) ? "主问题" : "追问")
                    .append(" 难度=").append(question.getDifficulty())
                    .append(" 状态=").append(question.getAnswerStatus()).append('\n')
                    .append("题目：").append(question.getContent()).append('\n');
            if ("ANSWERED".equals(question.getAnswerStatus())) {
                builder.append("回答：").append(question.getAnswer()).append('\n');
            } else {
                builder.append("回答：未作答（由系统记 0 分，不需要评分）\n");
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    // ---------- 状态与查询辅助 ----------

    private void requireAnswerable(String status) {
        if (RUNNING.contains(status)) {
            throw BizException.of(ErrorCode.INTERVIEW_TASK_IN_PROGRESS);
        }
        if ("COMPLETED".equals(status)) {
            throw BizException.of(ErrorCode.INTERVIEW_FINISHED);
        }
        if (!"IN_PROGRESS".equals(status)) {
            throw BizException.of(ErrorCode.INTERVIEW_STATE_INVALID);
        }
    }

    private void requireSwitchable(String status) {
        if (RUNNING.contains(status)) {
            throw BizException.of(ErrorCode.SESSION_SWITCH_FORBIDDEN);
        }
        if ("COMPLETED".equals(status)) {
            throw BizException.of(ErrorCode.SESSION_LOCKED);
        }
    }

    private static BizException submitGuard(String status) {
        if ("PREPARING".equals(status) || "GENERATING".equals(status)) {
            return BizException.of(ErrorCode.INTERVIEW_TASK_IN_PROGRESS);
        }
        if ("SUBMITTED".equals(status) || "COMPLETED".equals(status)) {
            return BizException.of(ErrorCode.INTERVIEW_FINISHED);
        }
        return BizException.of(ErrorCode.INTERVIEW_STATE_INVALID);
    }

    private void bumpAnswered(Long sessionId) {
        sessionMapper.update(null, Wrappers.<InterviewSession>lambdaUpdate()
                .eq(InterviewSession::getId, sessionId)
                .setSql("answered_count = answered_count + 1")
                .set(InterviewSession::getUpdatedAt, OffsetDateTime.now()));
    }

    private void finishQuestions(Long sessionId, String status, String error, int retryCount) {
        sessionMapper.update(null, Wrappers.<InterviewSession>lambdaUpdate()
                .eq(InterviewSession::getId, sessionId)
                .set(InterviewSession::getStatus, status)
                .set(InterviewSession::getError, truncate(error))
                .set(InterviewSession::getRetryCount, retryCount)
                .set(InterviewSession::getUpdatedAt, OffsetDateTime.now()));
    }

    private void finishFollowUp(Long sessionId, String error, int retryCount) {
        sessionMapper.update(null, Wrappers.<InterviewSession>lambdaUpdate()
                .eq(InterviewSession::getId, sessionId)
                .eq(InterviewSession::getStatus, "GENERATING")
                .set(InterviewSession::getStatus, "IN_PROGRESS")
                .set(InterviewSession::getError, truncate(error))
                .set(InterviewSession::getRetryCount, retryCount)
                .set(InterviewSession::getUpdatedAt, OffsetDateTime.now()));
    }

    private void finishEvaluation(Long sessionId, String error, int retryCount) {
        sessionMapper.update(null, Wrappers.<InterviewSession>lambdaUpdate()
                .eq(InterviewSession::getId, sessionId)
                .set(InterviewSession::getEvaluateStatus, "FAILED")
                .set(InterviewSession::getEvaluateError, truncate(error))
                .set(InterviewSession::getEvaluateRetryCount, retryCount)
                .set(InterviewSession::getUpdatedAt, OffsetDateTime.now()));
    }

    private InterviewSession requireSession(Long sessionId) {
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "面试会话不存在: " + sessionId);
        }
        return session;
    }

    private com.jobpilot.modules.interview.entity.InterviewQuestion requireQuestion(Long sessionId,
                                                                                  Integer questionIndex) {
        var question = questionMapper.selectOne(Wrappers
                .<com.jobpilot.modules.interview.entity.InterviewQuestion>lambdaQuery()
                .eq(com.jobpilot.modules.interview.entity.InterviewQuestion::getSessionId, sessionId)
                .eq(com.jobpilot.modules.interview.entity.InterviewQuestion::getQuestionIndex, questionIndex));
        if (question == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "题目不存在: " + questionIndex);
        }
        return question;
    }

    private List<com.jobpilot.modules.interview.entity.InterviewQuestion> questions(Long sessionId) {
        return questionMapper.selectList(Wrappers
                .<com.jobpilot.modules.interview.entity.InterviewQuestion>lambdaQuery()
                .eq(com.jobpilot.modules.interview.entity.InterviewQuestion::getSessionId, sessionId)
                .orderByAsc(com.jobpilot.modules.interview.entity.InterviewQuestion::getQuestionIndex));
    }

    private List<String> historyTopics(String direction, Long excludeSessionId) {
        List<Long> ids = sessionMapper.selectList(Wrappers.<InterviewSession>lambdaQuery()
                        .eq(InterviewSession::getDirection, direction)
                        .ne(InterviewSession::getId, excludeSessionId)
                        .orderByDesc(InterviewSession::getCreatedAt)
                        .last("LIMIT " + MAX_HISTORY_SESSIONS))
                .stream().map(InterviewSession::getId).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return questionMapper.selectList(Wrappers
                        .<com.jobpilot.modules.interview.entity.InterviewQuestion>lambdaQuery()
                        .in(com.jobpilot.modules.interview.entity.InterviewQuestion::getSessionId, ids)
                        .isNotNull(com.jobpilot.modules.interview.entity.InterviewQuestion::getTopicSummary))
                .stream()
                .map(com.jobpilot.modules.interview.entity.InterviewQuestion::getTopicSummary)
                .filter(topic -> !blank(topic))
                .distinct().limit(MAX_HISTORY_TOPICS).toList();
    }

    private LlmSceneService.SceneModel resolveTarget(String providerKey, String model) {
        if (blank(providerKey) && blank(model)) {
            return sceneService.resolve(LlmScene.INTERVIEW_QUESTION);
        }
        if (blank(providerKey) || blank(model)) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "providerKey 与 model 需同时提供");
        }
        requireEnabledModel(providerKey, model);
        return new LlmSceneService.SceneModel(providerKey, model);
    }

    private void requireEnabledModel(String providerKey, String model) {
        LlmProvider provider = providerMapper.selectOne(Wrappers.<LlmProvider>lambdaQuery()
                .eq(LlmProvider::getProviderKey, providerKey));
        if (provider == null || !Boolean.TRUE.equals(provider.getEnabled())) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "Provider 不存在或已禁用: " + providerKey);
        }
        Long matched = modelMapper.selectCount(Wrappers.<LlmModel>lambdaQuery()
                .eq(LlmModel::getProviderId, provider.getId())
                .eq(LlmModel::getModelName, model)
                .eq(LlmModel::getEnabled, true));
        if (matched == null || matched == 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "模型不存在或已禁用，或不属于该 Provider: " + model);
        }
    }

    /** 提交到有界线程池；队列满返回 false，由调用方回退状态，避免任务永久卡在执行中。 */
    private boolean submit(Runnable worker) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        try {
            executor.execute(() -> {
                if (context != null) {
                    MDC.setContextMap(context);
                }
                try {
                    worker.run();
                } catch (RuntimeException ex) {
                    log.warn("面试异步任务异常", ex);
                } finally {
                    MDC.clear();
                }
            });
            return true;
        } catch (RuntimeException ex) {
            log.warn("面试异步任务入队失败", ex);
            return false;
        }
    }

    // ---------- 响应组装 ----------

    private Map<Long, Refs> refs(List<InterviewSession> sessions) {
        Map<Long, Refs> result = new HashMap<>();
        sessions.forEach(session -> result.put(session.getId(),
                refs(session.getResumeId(), session.getJobPositionId())));
        return result;
    }

    private Refs refs(Long resumeId, Long jobPositionId) {
        String resumeName = null;
        if (resumeId != null) {
            Resume resume = resumeMapper.selectById(resumeId);
            resumeName = resume == null ? null : resume.getFileName();
        }
        String title = null;
        String company = null;
        if (jobPositionId != null) {
            JobPosition job = jobMapper.selectById(jobPositionId);
            title = job == null ? null : job.getTitle();
            if (job != null && job.getCompanyId() != null) {
                Company entity = companyMapper.selectById(job.getCompanyId());
                company = entity == null ? null : entity.getName();
            }
        }
        return new Refs(resumeName, title, company);
    }

    private com.jobpilot.modules.interview.dto.InterviewSession toSession(InterviewSession session, Refs refs) {
        Refs value = refs == null ? new Refs(null, null, null) : refs;
        return new com.jobpilot.modules.interview.dto.InterviewSession(session.getId(), session.getDirection(),
                session.getDirectionLabel(),
                session.getResumeId(), value.resumeFileName(), session.getJobPositionId(), value.jobPositionTitle(),
                value.companyName(), session.getStatus(), session.getEvaluateStatus(), session.getQuestionCount(),
                session.getFollowUpCount(), session.getAnsweredCount(), session.getCurrentProviderKey(),
                session.getCurrentModel(), session.getCreatedAt(), session.getUpdatedAt());
    }

    private InterviewSessionDetail toDetail(InterviewSession session, Refs refs,
                                            List<com.jobpilot.modules.interview.entity.InterviewQuestion> questions) {
        Refs value = refs == null ? new Refs(null, null, null) : refs;
        return new InterviewSessionDetail(session.getId(), session.getDirection(), session.getDirectionLabel(),
                session.getResumeId(), value.resumeFileName(), session.getJobPositionId(), value.jobPositionTitle(),
                value.companyName(), session.getStatus(), session.getEvaluateStatus(), session.getQuestionCount(),
                session.getFollowUpCount(), session.getAnsweredCount(), session.getCurrentProviderKey(),
                session.getCurrentModel(), session.getCreatedAt(), session.getUpdatedAt(), session.getError(),
                session.getRetryCount(), session.getEvaluateError(), session.getEvaluateRetryCount(),
                questions.stream().map(this::toQuestion).toList());
    }

    private InterviewQuestion toQuestion(com.jobpilot.modules.interview.entity.InterviewQuestion question) {
        return new InterviewQuestion(question.getQuestionIndex(), question.getParentIndex(), question.getType(),
                question.getContent(), question.getTopicSummary(), question.getDifficulty(),
                question.getAnswerStatus(), question.getAnswer(), question.getScore(), question.getFeedback(),
                question.getReferenceAnswer(), strings(question.getKeyPoints()),
                question.getLlmProvider(), question.getLlmModel());
    }

    private record Refs(String resumeFileName, String jobPositionTitle, String companyName) {
    }

    // ---------- 文本与 JSON 辅助 ----------

    /** JD 语料：结构化字段 + 原文，用于方向匹配与出题上下文。 */
    private static String jdCorpus(JobPosition job) {
        StringBuilder builder = new StringBuilder();
        append(builder, "岗位名称", job.getTitle());
        append(builder, "城市", job.getCity());
        append(builder, "经验要求", job.getExperience());
        append(builder, "学历要求", job.getEducation());
        append(builder, "必备技能", job.getMustHaveSkills());
        append(builder, "加分技能", job.getNiceToHaveSkills());
        append(builder, "职责摘要", job.getResponsibility());
        builder.append("\nJD 原文：\n").append(truncate(job.getRawText(), MAX_JD_CHARS));
        return builder.toString();
    }

    private static void append(StringBuilder builder, String label, String value) {
        if (!blank(value)) {
            builder.append(label).append('：').append(value).append('\n');
        }
    }

    private static String summarize(String content) {
        String value = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        return value.length() <= 40 ? value : value.substring(0, 40);
    }

    private static String difficulty(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return Set.of("EASY", "MEDIUM", "HARD").contains(normalized) ? normalized : "MEDIUM";
    }

    private static int clamp(Integer score) {
        return score == null ? 0 : Math.max(0, Math.min(100, score));
    }

    /** 文字清单兜底：去空、去重（保序）、单条截断，最多 20 条。 */
    private static List<String> strings(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(value -> !blank(value))
                .map(value -> truncate(value.trim(), 200)).distinct().limit(20).toList();
    }

    private static List<String> strings(String json) {
        if (blank(json)) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static List<InterviewEvaluationCategory> categories(String json) {
        if (blank(json)) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<InterviewEvaluationCategory>>() { });
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception ex) {
            return "[]";
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

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
