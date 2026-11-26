package com.jobpilot.modules.review.service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.api.PageResult;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.common.llm.ChatMessage;
import com.jobpilot.common.llm.ChatResult;
import com.jobpilot.common.llm.LlmClient;
import com.jobpilot.common.llm.StructuredOutputInvoker;
import com.jobpilot.infrastructure.asr.AsrClient;
import com.jobpilot.infrastructure.llm.LlmCallLogger;
import com.jobpilot.infrastructure.llm.ProviderClientRegistry;
import com.jobpilot.infrastructure.storage.FileStorage;
import com.jobpilot.modules.application.entity.JobApplication;
import com.jobpilot.modules.application.mapper.JobApplicationMapper;
import com.jobpilot.modules.company.entity.Company;
import com.jobpilot.modules.company.mapper.CompanyMapper;
import com.jobpilot.modules.job.entity.JobPosition;
import com.jobpilot.modules.job.mapper.JobPositionMapper;
import com.jobpilot.modules.llm.dto.LlmScene;
import com.jobpilot.modules.llm.service.LlmSceneService;
import com.jobpilot.modules.review.ai.AiReviewAnalysis;
import com.jobpilot.modules.review.dto.ImprovementActionDraftResponse;
import com.jobpilot.modules.review.dto.ReviewAnalysisResponse;
import com.jobpilot.modules.review.dto.ReviewAnalysisUpdateRequest;
import com.jobpilot.modules.review.dto.ReviewDimensionScoreResponse;
import com.jobpilot.modules.review.dto.ReviewQuestionResponse;
import com.jobpilot.modules.review.dto.ReviewSessionDetailResponse;
import com.jobpilot.modules.review.dto.ReviewSessionListItemResponse;
import com.jobpilot.modules.review.dto.ReviewSessionStatusResponse;
import com.jobpilot.modules.review.dto.ReviewSummaryResponse;
import com.jobpilot.modules.review.dto.ReviewTagResponse;
import com.jobpilot.modules.review.dto.ReviewTranscriptSegmentResponse;
import com.jobpilot.modules.review.dto.UpdateReviewSessionRequest;
import com.jobpilot.modules.review.entity.InterviewEvent;
import com.jobpilot.modules.review.entity.ReviewAnalysis;
import com.jobpilot.modules.review.entity.ReviewSession;
import com.jobpilot.modules.review.mapper.InterviewEventMapper;
import com.jobpilot.modules.review.mapper.ReviewAnalysisMapper;
import com.jobpilot.modules.review.mapper.ReviewSessionMapper;
import com.jobpilot.modules.review.prompt.ReviewPromptFactory;
import com.jobpilot.modules.review.taxonomy.ReviewTaxonomyCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 真实面试复盘（FR4）：转写 / 笔记 → 异步结构化分析 → 确认并生成改进行动。
 *
 * <p>支持转写文本、手动笔记与音频三种输入；音频先走 ASR，转写失败可用补录文本降级分析。
 * <p>分析优先使用 REVIEW_ANALYZE 场景绑定，未绑定时回落全局默认；自动重试 ≤3 次；
 * 分析结果带 Prompt / rubric / 词表版本，重跑覆盖。
 */
@Service
public class ReviewSessionService {

    private static final Logger log = LoggerFactory.getLogger(ReviewSessionService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final int MAX_RETRIES = 3;
    private static final long MAX_AUDIO_BYTES = 100L * 1024 * 1024;
    private static final Set<String> AUDIO_TYPES = Set.of("mp3", "wav", "m4a");
    private static final Set<String> ROUND_TYPES = Set.of("HR", "TECH", "CROSS", "DIRECTOR", "FINAL", "OTHER");
    private static final Set<String> FORMATS = Set.of("PHONE", "VIDEO", "ONSITE", "OTHER");

    private final ReviewSessionMapper sessionMapper;
    private final ReviewAnalysisMapper analysisMapper;
    private final InterviewEventMapper eventMapper;
    private final InterviewEventService eventService;
    private final ImprovementActionService actionService;
    private final JobApplicationMapper applicationMapper;
    private final JobPositionMapper jobMapper;
    private final CompanyMapper companyMapper;
    private final LlmSceneService sceneService;
    private final ProviderClientRegistry registry;
    private final LlmCallLogger callLogger;
    private final ReviewPromptFactory prompts;
    private final AsrClient asrClient;
    private final FileStorage storage;
    private final ThreadPoolTaskExecutor executor;
    private final ThreadPoolTaskExecutor asrExecutor;

    public ReviewSessionService(ReviewSessionMapper sessionMapper, ReviewAnalysisMapper analysisMapper,
                                InterviewEventMapper eventMapper, InterviewEventService eventService,
                                ImprovementActionService actionService, JobApplicationMapper applicationMapper,
                                JobPositionMapper jobMapper, CompanyMapper companyMapper,
                                LlmSceneService sceneService, ProviderClientRegistry registry,
                                LlmCallLogger callLogger, ReviewPromptFactory prompts, AsrClient asrClient,
                                FileStorage storage,
                                @Qualifier("jobpilotTaskExecutor") ThreadPoolTaskExecutor executor,
                                @Qualifier("asrTaskExecutor") ThreadPoolTaskExecutor asrExecutor) {
        this.sessionMapper = sessionMapper;
        this.analysisMapper = analysisMapper;
        this.eventMapper = eventMapper;
        this.eventService = eventService;
        this.actionService = actionService;
        this.applicationMapper = applicationMapper;
        this.jobMapper = jobMapper;
        this.companyMapper = companyMapper;
        this.sceneService = sceneService;
        this.registry = registry;
        this.callLogger = callLogger;
        this.prompts = prompts;
        this.asrClient = asrClient;
        this.storage = storage;
        this.executor = executor;
        this.asrExecutor = asrExecutor;
    }

    /**
     * 创建复盘：音频、转写、笔记三选一（音频与另两者互斥）。
     * 已有投递 / 岗位时复用或自动创建轮次；都没有时按公司 + 岗位名快速新建手动岗位。
     * 同一轮次只允许一条复盘（40917）。
     */
    public ReviewSessionListItemResponse create(MultipartFile audio, String transcript, String notes,
                                                Long applicationId, Long jobPositionId, Long companyId,
                                                String companyName, String jobTitle, Integer roundNo,
                                                String roundType, String interviewerRole, String format,
                                                OffsetDateTime interviewTime, String idempotencyKey) {
        boolean hasAudio = audio != null && !audio.isEmpty();
        String transcriptText = text(transcript, 200000);
        String noteText = text(notes, 20000);
        if (hasAudio && (transcriptText != null || noteText != null)) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "audio 与 transcript / notes 只能三选一");
        }
        if (!hasAudio && transcriptText == null && noteText == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "audio / transcript / notes 至少提供一个");
        }
        String audioExt = hasAudio ? audioExtension(audio) : null;
        String key = text(idempotencyKey, 64);
        if (key != null) {
            ReviewSession existing = sessionMapper.selectOne(Wrappers.<ReviewSession>lambdaQuery()
                    .eq(ReviewSession::getIdempotencyKey, key));
            if (existing != null) {
                return toItems(List.of(existing)).get(0);
            }
        }
        InterviewEvent event = applicationId == null && jobPositionId == null
                ? eventService.ensureEventForManualJob(companyId, companyName, jobTitle, roundNo, roundType,
                        interviewerRole, format, interviewTime)
                : eventService.ensureEvent(applicationId, jobPositionId, roundNo, roundType,
                        interviewerRole, format, interviewTime);
        Long reviews = sessionMapper.selectCount(Wrappers.<ReviewSession>lambdaQuery()
                .eq(ReviewSession::getEventId, event.getId()));
        if (reviews != null && reviews > 0) {
            throw BizException.of(ErrorCode.REVIEW_ROUND_EXISTS, "该轮面试已存在复盘记录");
        }
        String storageKey = null;
        if (hasAudio) {
            storageKey = "reviews/" + java.util.UUID.randomUUID() + "." + audioExt;
            saveAudio(storageKey, audio);
        }
        OffsetDateTime now = OffsetDateTime.now();
        ReviewSession entity = new ReviewSession();
        entity.setUserId(1L);
        entity.setEventId(event.getId());
        entity.setInputType(hasAudio ? "AUDIO" : transcriptText != null ? "TEXT" : "NOTES");
        entity.setAudioStorageKey(storageKey);
        entity.setAudioFileName(hasAudio ? text(audio.getOriginalFilename(), 255) : null);
        entity.setAudioSize(hasAudio ? audio.getSize() : null);
        entity.setTranscript(transcriptText);
        entity.setNotes(noteText);
        entity.setAsrStatus(hasAudio ? "PENDING" : "SKIPPED");
        entity.setAnalyzeStatus("PENDING");
        entity.setStatus("UPLOADED");
        entity.setAsrRetryCount(0);
        entity.setAnalyzeRetryCount(0);
        entity.setConfirmed(false);
        entity.setIdempotencyKey(key);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        try {
            sessionMapper.insert(entity);
        } catch (DuplicateKeyException ex) {
            if (storageKey != null) {
                deleteAudioQuietly(storageKey);
            }
            if (key != null) {
                ReviewSession existing = sessionMapper.selectOne(Wrappers.<ReviewSession>lambdaQuery()
                        .eq(ReviewSession::getIdempotencyKey, key));
                if (existing != null) {
                    return toItems(List.of(existing)).get(0);
                }
            }
            throw BizException.of(ErrorCode.REVIEW_ROUND_EXISTS, "该轮面试已存在复盘记录");
        }
        if (hasAudio) {
            submitTranscription(entity.getId());
        } else if (hasDefaultModel()) {
            try {
                startAnalysis(entity.getId());
            } catch (RuntimeException ex) {
                log.warn("复盘分析自动触发失败 reviewSessionId={}: {}", entity.getId(), ex.getMessage());
            }
        }
        return toItems(List.of(require(entity.getId()))).get(0);
    }

    public PageResult<ReviewSessionListItemResponse> page(Integer page, Integer size, String status,
                                                           Long applicationId) {
        int current = PageResult.normalizePage(page);
        int pageSize = PageResult.normalizeSize(size);
        List<Long> eventIds = null;
        if (applicationId != null) {
            eventIds = eventMapper.selectList(Wrappers.<InterviewEvent>lambdaQuery()
                            .eq(InterviewEvent::getApplicationId, applicationId))
                    .stream().map(InterviewEvent::getId).toList();
            if (eventIds.isEmpty()) {
                return PageResult.of(List.of(), 0, current, pageSize);
            }
        }
        Page<ReviewSession> rows = sessionMapper.selectPage(new Page<>(current, pageSize),
                Wrappers.<ReviewSession>lambdaQuery()
                        .eq(!blank(status), ReviewSession::getStatus, status)
                        .in(eventIds != null, ReviewSession::getEventId, eventIds)
                        .orderByDesc(ReviewSession::getCreatedAt).orderByDesc(ReviewSession::getId));
        return PageResult.of(toItems(rows.getRecords()), rows.getTotal(), current, pageSize);
    }

    public ReviewSessionDetailResponse detail(Long reviewSessionId) {
        ReviewSession session = require(reviewSessionId);
        ReviewSessionListItemResponse item = toItems(List.of(session)).get(0);
        return new ReviewSessionDetailResponse(item.id(), item.interviewEventId(), item.applicationId(),
                item.companyName(), item.jobTitle(), item.roundNo(), item.roundType(), item.inputType(),
                item.status(), item.asrStatus(), item.analyzeStatus(), item.asrRetryCount(),
                item.analyzeRetryCount(), item.audioDurationSeconds(), item.createdAt(), item.updatedAt(),
                session.getTranscript(), segmentsOf(session), analysisOf(session),
                Boolean.TRUE.equals(session.getConfirmed()), session.getAsrError(), session.getAnalyzeError(),
                session.getAudioFileName());
    }

    /** 修正转写 / 笔记 / 面试上下文；任务执行中禁止修改（40915）。 */
    public ReviewSessionDetailResponse update(Long reviewSessionId, UpdateReviewSessionRequest request) {
        ReviewSession session = require(reviewSessionId);
        if ("PROCESSING".equals(session.getAsrStatus()) || "PROCESSING".equals(session.getAnalyzeStatus())) {
            throw BizException.of(ErrorCode.REVIEW_TASK_IN_PROGRESS);
        }
        if (request.transcript() != null) {
            session.setTranscript(nodeText(request.transcript(), 200000));
        }
        if (request.notes() != null) {
            session.setNotes(nodeText(request.notes(), 20000));
        }
        InterviewEvent event = eventMapper.selectById(session.getEventId());
        boolean eventDirty = false;
        if (event != null && request.interviewerRole() != null) {
            event.setInterviewerRole(nodeText(request.interviewerRole(), 64));
            eventDirty = true;
        }
        if (event != null && request.roundType() != null) {
            event.setRoundType(enumNode(request.roundType(), ROUND_TYPES, "roundType"));
            eventDirty = true;
        }
        if (event != null && request.format() != null) {
            event.setFormat(enumNode(request.format(), FORMATS, "format"));
            eventDirty = true;
        }
        if (event != null && request.interviewTime() != null) {
            event.setInterviewTime(parseTime(request.interviewTime()));
            eventDirty = true;
        }
        if (eventDirty) {
            event.setUpdatedAt(OffsetDateTime.now());
            eventMapper.updateById(event);
        }
        session.setUpdatedAt(OffsetDateTime.now());
        sessionMapper.updateById(session);
        return detail(reviewSessionId);
    }

    /** 硬删除复盘：分析与由该复盘生成的改进行动由外键级联清理，同时删除磁盘音频。 */
    public void delete(Long reviewSessionId) {
        ReviewSession session = require(reviewSessionId);
        if (!blank(session.getAudioStorageKey())) {
            deleteAudioQuietly(session.getAudioStorageKey());
        }
        sessionMapper.deleteById(reviewSessionId);
    }

    public ReviewSessionStatusResponse status(Long reviewSessionId) {
        ReviewSession session = require(reviewSessionId);
        return new ReviewSessionStatusResponse(session.getId(), session.getStatus(), session.getAsrStatus(),
                session.getAnalyzeStatus(), count(session.getAsrRetryCount()), count(session.getAnalyzeRetryCount()),
                session.getAsrError(), session.getAnalyzeError(), session.getUpdatedAt());
    }

    /** 手动触发 / 重新分析：ASR 未完成或分析执行中返回 40915；重新分析覆盖原结果。 */
    public ReviewSessionListItemResponse analyze(Long reviewSessionId) {
        ReviewSession session = require(reviewSessionId);
        if ("PROCESSING".equals(session.getAsrStatus()) || "PROCESSING".equals(session.getAnalyzeStatus())) {
            throw BizException.of(ErrorCode.REVIEW_TASK_IN_PROGRESS);
        }
        if ("AUDIO".equals(session.getInputType()) && !"COMPLETED".equals(session.getAsrStatus())) {
            boolean hasManualText = !blank(session.getTranscript()) || !blank(session.getNotes());
            if (!hasManualText) {
                String message = "FAILED".equals(session.getAsrStatus())
                        ? "转写失败，请先重试转写或补充转写文本" : "转写尚未完成，请稍后再试";
                throw BizException.of(ErrorCode.REVIEW_TASK_IN_PROGRESS, message);
            }
            // 转写失败后允许用用户补录的文本继续分析（FR4.1 降级），转写仍在进行时仍禁止，避免并发覆盖
            if (!"FAILED".equals(session.getAsrStatus())) {
                throw BizException.of(ErrorCode.REVIEW_TASK_IN_PROGRESS, "转写尚未完成，请稍后再试或补充转写文本");
            }
        }
        if (blank(session.getTranscript()) && blank(session.getNotes())) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "缺少可分析的转写文本或笔记");
        }
        requireDefaultModel();
        startAnalysis(reviewSessionId);
        return toItems(List.of(require(reviewSessionId))).get(0);
    }

    /** 手动重试转写（FR4.8）：仅音频复盘且转写未完成时可用；转写完成后不再允许重复转写。 */
    public ReviewSessionListItemResponse transcribe(Long reviewSessionId) {
        ReviewSession session = require(reviewSessionId);
        if (!"AUDIO".equals(session.getInputType()) || blank(session.getAudioStorageKey())) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "仅音频复盘支持重新转写");
        }
        if ("PROCESSING".equals(session.getAsrStatus()) || "PROCESSING".equals(session.getAnalyzeStatus())) {
            throw BizException.of(ErrorCode.REVIEW_TASK_IN_PROGRESS);
        }
        if ("COMPLETED".equals(session.getAsrStatus())) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "转写已完成，如需重新分析请调用 /analyze");
        }
        if (!asrClient.configured()) {
            throw BizException.of(ErrorCode.BAD_REQUEST,
                    "未配置语音转写服务：请在 .env 设置 ASR_BASE_URL / ASR_API_KEY，或补充转写文本");
        }
        int updated = sessionMapper.update(null, Wrappers.<ReviewSession>lambdaUpdate()
                .eq(ReviewSession::getId, reviewSessionId)
                .ne(ReviewSession::getAsrStatus, "PROCESSING")
                .set(ReviewSession::getAsrStatus, "PROCESSING")
                .set(ReviewSession::getAsrRetryCount, 0)
                .set(ReviewSession::getAsrError, null)
                .set(ReviewSession::getStatus, "TRANSCRIBING")
                .set(ReviewSession::getUpdatedAt, OffsetDateTime.now()));
        if (updated == 0) {
            throw BizException.of(ErrorCode.REVIEW_TASK_IN_PROGRESS);
        }
        submitAsrTask(reviewSessionId);
        return toItems(List.of(require(reviewSessionId))).get(0);
    }

    /** 确认 / 修正分析：未完成 40916；confirmed=true 时按草稿创建或更新改进行动。 */
    public ReviewSessionDetailResponse updateAnalysis(Long reviewSessionId, ReviewAnalysisUpdateRequest request) {
        ReviewSession session = require(reviewSessionId);
        ReviewAnalysis row = analysisMapper.selectOne(Wrappers.<ReviewAnalysis>lambdaQuery()
                .eq(ReviewAnalysis::getReviewSessionId, reviewSessionId));
        if (!"COMPLETED".equals(session.getAnalyzeStatus()) || row == null) {
            throw BizException.of(ErrorCode.REVIEW_NOT_READY);
        }
        List<ReviewQuestionResponse> questions = normalizeQuestions(request.questions());
        List<ReviewTagResponse> topics = normalizeTags(request.topics(), true);
        List<ReviewTagResponse> lossTags = normalizeTags(request.lossTags(), false);
        ReviewSummaryResponse summary = normalizeSummary(request.summary());
        List<ImprovementActionDraftResponse> actions = normalizeActions(request.actions());
        boolean confirmed = Boolean.TRUE.equals(request.confirmed());
        ReviewAnalysisResponse analysis = new ReviewAnalysisResponse(questions, topics, lossTags, summary, actions,
                row.getPromptVersion(), row.getRubricVersion(), row.getTaxonomyVersion(), confirmed,
                Boolean.TRUE.equals(row.getDegraded()), row.getLlmProvider(), row.getLlmModel());
        row.setAnalysis(write(analysis));
        row.setUpdatedAt(OffsetDateTime.now());
        analysisMapper.updateById(row);
        session.setConfirmed(confirmed);
        session.setUpdatedAt(OffsetDateTime.now());
        sessionMapper.updateById(session);
        if (confirmed) {
            InterviewEvent event = eventMapper.selectById(session.getEventId());
            actionService.syncFromDrafts(reviewSessionId, session.getEventId(),
                    event == null ? null : event.getApplicationId(), actions);
        }
        return detail(reviewSessionId);
    }

    /** 投递时间线：该投递下全部复盘，按创建时间倒序。 */
    public List<ReviewSessionListItemResponse> listByApplication(Long applicationId) {
        List<Long> eventIds = eventMapper.selectList(Wrappers.<InterviewEvent>lambdaQuery()
                        .eq(InterviewEvent::getApplicationId, applicationId))
                .stream().map(InterviewEvent::getId).toList();
        if (eventIds.isEmpty()) {
            return List.of();
        }
        return toItems(sessionMapper.selectList(Wrappers.<ReviewSession>lambdaQuery()
                .in(ReviewSession::getEventId, eventIds)
                .orderByDesc(ReviewSession::getCreatedAt).orderByDesc(ReviewSession::getId)));
    }

    /** 置为分析中并提交异步任务；条件更新避免重复入队。 */
    private void startAnalysis(Long reviewSessionId) {
        int updated = sessionMapper.update(null, Wrappers.<ReviewSession>lambdaUpdate()
                .eq(ReviewSession::getId, reviewSessionId)
                .ne(ReviewSession::getAnalyzeStatus, "PROCESSING")
                .set(ReviewSession::getAnalyzeStatus, "PROCESSING")
                .set(ReviewSession::getAnalyzeRetryCount, 0)
                .set(ReviewSession::getAnalyzeError, null)
                .set(ReviewSession::getStatus, "ANALYZING")
                .set(ReviewSession::getUpdatedAt, OffsetDateTime.now()));
        if (updated == 0) {
            throw BizException.of(ErrorCode.REVIEW_TASK_IN_PROGRESS);
        }
        Map<String, String> context = MDC.getCopyOfContextMap();
        try {
            executor.execute(() -> {
                if (context != null) {
                    MDC.setContextMap(context);
                }
                try {
                    analyzeWorker(reviewSessionId);
                } finally {
                    MDC.clear();
                }
            });
        } catch (RuntimeException ex) {
            sessionMapper.update(null, Wrappers.<ReviewSession>lambdaUpdate()
                    .eq(ReviewSession::getId, reviewSessionId)
                    .set(ReviewSession::getAnalyzeStatus, "PENDING")
                    .set(ReviewSession::getStatus, "UPLOADED")
                    .set(ReviewSession::getAnalyzeError, "分析任务队列已满，请稍后重试")
                    .set(ReviewSession::getUpdatedAt, OffsetDateTime.now()));
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "分析任务队列已满，请稍后重试");
        }
    }

    /** 提交 ASR 任务：置转写中并投递到独立线程池；队列满时回退为待转写并记录错误。 */
    private void submitTranscription(Long reviewSessionId) {
        int updated = sessionMapper.update(null, Wrappers.<ReviewSession>lambdaUpdate()
                .eq(ReviewSession::getId, reviewSessionId)
                .ne(ReviewSession::getAsrStatus, "PROCESSING")
                .set(ReviewSession::getAsrStatus, "PROCESSING")
                .set(ReviewSession::getAsrRetryCount, 0)
                .set(ReviewSession::getAsrError, null)
                .set(ReviewSession::getStatus, "TRANSCRIBING")
                .set(ReviewSession::getUpdatedAt, OffsetDateTime.now()));
        if (updated == 0) {
            return;
        }
        submitAsrTask(reviewSessionId);
    }

    private void submitAsrTask(Long reviewSessionId) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        try {
            asrExecutor.execute(() -> {
                if (context != null) {
                    MDC.setContextMap(context);
                }
                try {
                    asrWorker(reviewSessionId);
                } finally {
                    MDC.clear();
                }
            });
        } catch (RuntimeException ex) {
            sessionMapper.update(null, Wrappers.<ReviewSession>lambdaUpdate()
                    .eq(ReviewSession::getId, reviewSessionId)
                    .set(ReviewSession::getAsrStatus, "PENDING")
                    .set(ReviewSession::getStatus, "UPLOADED")
                    .set(ReviewSession::getAsrError, "转写任务队列已满，请稍后重试")
                    .set(ReviewSession::getUpdatedAt, OffsetDateTime.now()));
            log.warn("转写任务入队失败 reviewSessionId={}", reviewSessionId, ex);
        }
    }

    /** ASR worker：首次 + 最多 3 次重试；成功后写回转写并自动触发分析。 */
    private void asrWorker(Long reviewSessionId) {
        ReviewSession session = sessionMapper.selectById(reviewSessionId);
        if (session == null || !"PROCESSING".equals(session.getAsrStatus())
                || blank(session.getAudioStorageKey())) {
            return;
        }
        byte[] audio;
        try {
            audio = storage.load(session.getAudioStorageKey());
        } catch (Exception ex) {
            finishAsr(reviewSessionId, "音频读取失败，请重新创建复盘或补充转写文本", 0);
            return;
        }
        String contentType = audioContentType(session.getAudioStorageKey());
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (sessionMapper.selectById(reviewSessionId) == null) {
                log.info("复盘 {} 已删除，终止转写", reviewSessionId);
                return;
            }
            try {
                String transcript = asrClient.transcribe(session.getAudioFileName(), contentType, audio);
                sessionMapper.update(null, Wrappers.<ReviewSession>lambdaUpdate()
                        .eq(ReviewSession::getId, reviewSessionId)
                        .set(ReviewSession::getTranscript, text(transcript, 200000))
                        .set(ReviewSession::getAsrStatus, "COMPLETED")
                        .set(ReviewSession::getAsrError, null)
                        .set(ReviewSession::getAsrRetryCount, attempt)
                        .set(ReviewSession::getStatus, "UPLOADED")
                        .set(ReviewSession::getUpdatedAt, OffsetDateTime.now()));
                log.info("复盘转写完成 reviewSessionId={} 第 {} 次", reviewSessionId, attempt + 1);
                if (hasDefaultModel()) {
                    try {
                        startAnalysis(reviewSessionId);
                    } catch (RuntimeException ex) {
                        log.warn("转写完成后自动分析失败 reviewSessionId={}: {}", reviewSessionId, ex.getMessage());
                    }
                }
                return;
            } catch (Exception ex) {
                int retries = Math.min(attempt + 1, MAX_RETRIES);
                if (attempt == MAX_RETRIES) {
                    finishAsr(reviewSessionId, truncate(ex.getMessage(), 400), retries);
                } else {
                    sessionMapper.update(null, Wrappers.<ReviewSession>lambdaUpdate()
                            .eq(ReviewSession::getId, reviewSessionId)
                            .set(ReviewSession::getAsrRetryCount, retries)
                            .set(ReviewSession::getAsrError, truncate(ex.getMessage(), 500))
                            .set(ReviewSession::getUpdatedAt, OffsetDateTime.now()));
                }
                log.warn("复盘转写失败 reviewSessionId={} 第 {} 次: {}", reviewSessionId, attempt + 1,
                        ex.getMessage());
            }
        }
    }

    private void finishAsr(Long reviewSessionId, String error, int retryCount) {
        sessionMapper.update(null, Wrappers.<ReviewSession>lambdaUpdate()
                .eq(ReviewSession::getId, reviewSessionId)
                .set(ReviewSession::getAsrStatus, "FAILED")
                .set(ReviewSession::getStatus, "FAILED")
                .set(ReviewSession::getAsrError, "转写失败：" + (error == null ? "未知错误" : error)
                        + "；可重试转写或改用文本")
                .set(ReviewSession::getAsrRetryCount, retryCount)
                .set(ReviewSession::getUpdatedAt, OffsetDateTime.now()));
    }

    /** 校验扩展名与大小并落盘；不支持的类型返回 415，超过 100MB 返回 413。 */
    private String audioExtension(MultipartFile audio) {
        if (!asrClient.configured()) {
            throw BizException.of(ErrorCode.BAD_REQUEST,
                    "未配置语音转写服务：请在 .env 设置 ASR_BASE_URL / ASR_API_KEY，或改用转写文本 / 手动笔记");
        }
        String name = audio.getOriginalFilename();
        int dot = name == null ? -1 : name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        if (!AUDIO_TYPES.contains(ext)) {
            throw BizException.of(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "音频仅支持 mp3 / wav / m4a");
        }
        if (audio.getSize() > MAX_AUDIO_BYTES) {
            throw BizException.of(ErrorCode.PAYLOAD_TOO_LARGE, "音频不能超过 100MB");
        }
        return ext;
    }

    private void saveAudio(String storageKey, MultipartFile audio) {
        try {
            storage.save(storageKey, audio.getBytes());
        } catch (Exception ex) {
            log.warn("音频保存失败 key={}", storageKey, ex);
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "音频保存失败，请稍后重试");
        }
    }

    private void deleteAudioQuietly(String storageKey) {
        try {
            storage.delete(storageKey);
        } catch (Exception ex) {
            log.warn("音频删除失败 key={}: {}", storageKey, ex.getMessage());
        }
    }

    private static String audioContentType(String storageKey) {
        String lower = storageKey == null ? "" : storageKey.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".wav")) {
            return "audio/wav";
        }
        if (lower.endsWith(".m4a")) {
            return "audio/mp4";
        }
        return "audio/mpeg";
    }

    /** 异步 worker：首次 + 最多 3 次重试；成功保存分析并置 READY。 */
    private void analyzeWorker(Long reviewSessionId) {
        ReviewSession session = sessionMapper.selectById(reviewSessionId);
        if (session == null || !"PROCESSING".equals(session.getAnalyzeStatus())) {
            return;
        }
        LlmSceneService.SceneModel target;
        LlmClient client;
        try {
            target = resolveModel();
            client = registry.get(target.providerKey());
        } catch (Exception ex) {
            finish(reviewSessionId, "FAILED", truncate(ex.getMessage(), 500), 0);
            return;
        }
        InterviewEvent event = eventMapper.selectById(session.getEventId());
        JobApplication application = event == null ? null : applicationMapper.selectById(event.getApplicationId());
        JobPosition job = application == null ? null : jobMapper.selectById(application.getJobPositionId());
        Company company = job == null || job.getCompanyId() == null ? null : companyMapper.selectById(job.getCompanyId());
        String text = blank(session.getTranscript()) ? session.getNotes() : session.getTranscript();
        List<ChatMessage> messages = List.of(
                ChatMessage.system(prompts.system()),
                ChatMessage.user(prompts.user(company == null ? null : company.getName(),
                        job == null ? null : job.getTitle(), event == null ? null : event.getRoundType(),
                        event == null ? null : event.getInterviewerRole(), text)));
        StructuredOutputInvoker invoker = new StructuredOutputInvoker(client);
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (sessionMapper.selectById(reviewSessionId) == null) {
                log.info("复盘 {} 已删除，终止分析", reviewSessionId);
                return;
            }
            long start = System.nanoTime();
            try {
                StructuredOutputInvoker.Parsed<AiReviewAnalysis> parsed =
                        invoker.invokeWithUsage(target.model(), messages, AiReviewAnalysis.class);
                saveAnalysis(reviewSessionId, parsed.value(), target, attempt);
                ChatResult usage = parsed.result();
                callLogger.log("REVIEW_ANALYZE", target.providerKey(), target.model(),
                        usage.promptTokens(), usage.completionTokens(), usage.totalTokens(),
                        elapsed(start), true, null);
                return;
            } catch (Exception ex) {
                if (sessionMapper.selectById(reviewSessionId) == null) {
                    log.info("复盘 {} 已删除，终止分析", reviewSessionId);
                    return;
                }
                int retries = Math.min(attempt + 1, MAX_RETRIES);
                if (attempt == MAX_RETRIES) {
                    finish(reviewSessionId, "FAILED", truncate(ex.getMessage(), 500), retries);
                } else {
                    sessionMapper.update(null, Wrappers.<ReviewSession>lambdaUpdate()
                            .eq(ReviewSession::getId, reviewSessionId)
                            .set(ReviewSession::getAnalyzeRetryCount, retries)
                            .set(ReviewSession::getAnalyzeError, truncate(ex.getMessage(), 500))
                            .set(ReviewSession::getUpdatedAt, OffsetDateTime.now()));
                }
                callLogger.log("REVIEW_ANALYZE", target.providerKey(), target.model(),
                        null, null, null, elapsed(start), false, ex.getMessage());
                log.warn("复盘分析失败 reviewSessionId={} 第 {} 次: {}", reviewSessionId, attempt + 1, ex.getMessage());
            }
        }
    }

    /** 本地兜底：词表过滤、分数钳制、问题重编号、行动最多 3 条。 */
    private ReviewAnalysisResponse normalize(AiReviewAnalysis ai, LlmSceneService.SceneModel target) {
        List<ReviewQuestionResponse> questions = new ArrayList<>();
        if (ai.questions() != null) {
            int index = 1;
            for (AiReviewAnalysis.Question question : ai.questions()) {
                if (question == null || blank(question.question()) || questions.size() >= 50) {
                    continue;
                }
                List<ReviewDimensionScoreResponse> dimensions = new ArrayList<>();
                if (question.dimensions() != null) {
                    for (AiReviewAnalysis.Dimension dimension : question.dimensions()) {
                        if (dimension != null && ReviewTaxonomyCatalog.isDimension(dimension.dimension())) {
                            dimensions.add(new ReviewDimensionScoreResponse(dimension.dimension(),
                                    ReviewTaxonomyCatalog.dimensionLabel(dimension.dimension()),
                                    clamp(dimension.score())));
                        }
                    }
                }
                questions.add(new ReviewQuestionResponse(index++, truncate(question.question(), 2000),
                        truncate(question.answerSummary(), 4000), clamp(question.score()), dimensions,
                        topicKeys(question.topics()), lossKeys(question.lossTags()),
                        truncate(question.evidence(), 1000), truncate(question.feedback(), 2000),
                        truncate(question.referenceAnswer(), 4000)));
            }
        }
        return new ReviewAnalysisResponse(questions, tags(ai.topics(), true), tags(ai.lossTags(), false),
                summary(ai.summary()), drafts(ai.actions()), ReviewPromptFactory.VERSION,
                ReviewTaxonomyCatalog.RUBRIC_VERSION, ReviewTaxonomyCatalog.TAXONOMY_VERSION,
                false, false, target.providerKey(), target.model());
    }

    private void saveAnalysis(Long reviewSessionId, AiReviewAnalysis ai, LlmSceneService.SceneModel target,
                              int retryCount) {
        ReviewAnalysisResponse analysis = normalize(ai, target);
        String json = write(analysis);
        OffsetDateTime now = OffsetDateTime.now();
        ReviewAnalysis row = analysisMapper.selectOne(Wrappers.<ReviewAnalysis>lambdaQuery()
                .eq(ReviewAnalysis::getReviewSessionId, reviewSessionId));
        if (row == null) {
            row = new ReviewAnalysis();
            row.setReviewSessionId(reviewSessionId);
            row.setAnalysis(json);
            row.setPromptVersion(ReviewPromptFactory.VERSION);
            row.setRubricVersion(ReviewTaxonomyCatalog.RUBRIC_VERSION);
            row.setTaxonomyVersion(ReviewTaxonomyCatalog.TAXONOMY_VERSION);
            row.setLlmProvider(target.providerKey());
            row.setLlmModel(target.model());
            row.setDegraded(false);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            analysisMapper.insert(row);
        } else {
            row.setAnalysis(json);
            row.setPromptVersion(ReviewPromptFactory.VERSION);
            row.setRubricVersion(ReviewTaxonomyCatalog.RUBRIC_VERSION);
            row.setTaxonomyVersion(ReviewTaxonomyCatalog.TAXONOMY_VERSION);
            row.setLlmProvider(target.providerKey());
            row.setLlmModel(target.model());
            row.setDegraded(false);
            row.setUpdatedAt(now);
            analysisMapper.updateById(row);
        }
        sessionMapper.update(null, Wrappers.<ReviewSession>lambdaUpdate()
                .eq(ReviewSession::getId, reviewSessionId)
                .set(ReviewSession::getAnalyzeStatus, "COMPLETED")
                .set(ReviewSession::getStatus, "READY")
                .set(ReviewSession::getAnalyzeError, null)
                .set(ReviewSession::getAnalyzeRetryCount, retryCount)
                .set(ReviewSession::getConfirmed, false)
                .set(ReviewSession::getUpdatedAt, now));
    }

    private void finish(Long reviewSessionId, String status, String error, int retryCount) {
        sessionMapper.update(null, Wrappers.<ReviewSession>lambdaUpdate()
                .eq(ReviewSession::getId, reviewSessionId)
                .set(ReviewSession::getAnalyzeStatus, status)
                .set(ReviewSession::getStatus, "FAILED")
                .set(ReviewSession::getAnalyzeError, error)
                .set(ReviewSession::getAnalyzeRetryCount, retryCount)
                .set(ReviewSession::getUpdatedAt, OffsetDateTime.now()));
    }

    private List<ReviewSessionListItemResponse> toItems(List<ReviewSession> sessions) {
        if (sessions.isEmpty()) {
            return List.of();
        }
        Map<Long, InterviewEvent> events = lookup(sessions.stream()
                        .map(ReviewSession::getEventId).filter(Objects::nonNull).distinct().toList(),
                eventMapper::selectByIds, InterviewEvent::getId);
        Map<Long, JobApplication> applications = lookup(events.values().stream()
                        .map(InterviewEvent::getApplicationId).filter(Objects::nonNull).distinct().toList(),
                applicationMapper::selectByIds, JobApplication::getId);
        Map<Long, JobPosition> jobs = lookup(applications.values().stream()
                        .map(JobApplication::getJobPositionId).filter(Objects::nonNull).distinct().toList(),
                jobMapper::selectByIds, JobPosition::getId);
        Map<Long, Company> companies = lookup(jobs.values().stream()
                        .map(JobPosition::getCompanyId).filter(Objects::nonNull).distinct().toList(),
                companyMapper::selectByIds, Company::getId);
        return sessions.stream().map(session -> {
            InterviewEvent event = events.get(session.getEventId());
            JobApplication application = event == null ? null : applications.get(event.getApplicationId());
            JobPosition job = application == null ? null : jobs.get(application.getJobPositionId());
            Company company = job == null || job.getCompanyId() == null ? null : companies.get(job.getCompanyId());
            return new ReviewSessionListItemResponse(session.getId(), session.getEventId(),
                    application == null ? null : application.getId(),
                    company == null ? null : company.getName(), job == null ? null : job.getTitle(),
                    event == null ? null : event.getRoundNo(), event == null ? null : event.getRoundType(),
                    session.getInputType(), session.getStatus(), session.getAsrStatus(), session.getAnalyzeStatus(),
                    count(session.getAsrRetryCount()), count(session.getAnalyzeRetryCount()),
                    session.getAudioDurationSeconds(), session.getCreatedAt(), session.getUpdatedAt());
        }).toList();
    }

    private ReviewAnalysisResponse analysisOf(ReviewSession session) {
        ReviewAnalysis row = analysisMapper.selectOne(Wrappers.<ReviewAnalysis>lambdaQuery()
                .eq(ReviewAnalysis::getReviewSessionId, session.getId()));
        if (row == null || blank(row.getAnalysis())) {
            return null;
        }
        try {
            ReviewAnalysisResponse stored = MAPPER.readValue(row.getAnalysis(), ReviewAnalysisResponse.class);
            return new ReviewAnalysisResponse(stored.questions(), stored.topics(), stored.lossTags(),
                    stored.summary(), stored.actions(), row.getPromptVersion(), row.getRubricVersion(),
                    row.getTaxonomyVersion(), Boolean.TRUE.equals(session.getConfirmed()),
                    Boolean.TRUE.equals(row.getDegraded()), row.getLlmProvider(), row.getLlmModel());
        } catch (Exception ex) {
            log.warn("复盘分析 JSON 解析失败 reviewSessionId={}: {}", session.getId(), ex.getMessage());
            return null;
        }
    }

    private List<ReviewTranscriptSegmentResponse> segmentsOf(ReviewSession session) {
        if (blank(session.getTranscriptSegments())) {
            return List.of();
        }
        try {
            return MAPPER.readValue(session.getTranscriptSegments(),
                    MAPPER.getTypeFactory().constructCollectionType(List.class, ReviewTranscriptSegmentResponse.class));
        } catch (Exception ex) {
            log.warn("转写片段解析失败 reviewSessionId={}: {}", session.getId(), ex.getMessage());
            return List.of();
        }
    }

    private static List<ReviewQuestionResponse> normalizeQuestions(List<ReviewQuestionResponse> input) {
        List<ReviewQuestionResponse> questions = new ArrayList<>();
        int index = 1;
        for (ReviewQuestionResponse question : input) {
            if (question == null || blank(question.question())) {
                continue;
            }
            List<ReviewDimensionScoreResponse> dimensions = new ArrayList<>();
            if (question.dimensions() != null) {
                for (ReviewDimensionScoreResponse dimension : question.dimensions()) {
                    if (dimension == null || !ReviewTaxonomyCatalog.isDimension(dimension.dimension())) {
                        throw BizException.of(ErrorCode.BAD_REQUEST,
                                "评分维度不在受控词表内: " + (dimension == null ? null : dimension.dimension()));
                    }
                    dimensions.add(new ReviewDimensionScoreResponse(dimension.dimension(),
                            ReviewTaxonomyCatalog.dimensionLabel(dimension.dimension()), clamp(dimension.score())));
                }
            }
            questions.add(new ReviewQuestionResponse(index++, truncate(question.question(), 2000),
                    truncate(question.answerSummary(), 4000), clamp(question.score()), dimensions,
                    validatedKeys(question.topics(), true), validatedKeys(question.lossTags(), false),
                    truncate(question.evidence(), 1000), truncate(question.feedback(), 2000),
                    truncate(question.referenceAnswer(), 4000)));
            if (questions.size() >= 50) {
                break;
            }
        }
        return questions;
    }

    private static List<ReviewTagResponse> normalizeTags(List<ReviewTagResponse> input, boolean topic) {
        List<ReviewTagResponse> tags = new ArrayList<>();
        for (ReviewTagResponse tag : input) {
            if (tag == null || blank(tag.tag())) {
                continue;
            }
            boolean valid = topic ? ReviewTaxonomyCatalog.isTopic(tag.tag()) : ReviewTaxonomyCatalog.isLossTag(tag.tag());
            if (!valid) {
                throw BizException.of(ErrorCode.BAD_REQUEST,
                        (topic ? "知识点标签" : "失分类型") + "不在受控词表内: " + tag.tag());
            }
            tags.add(new ReviewTagResponse(tag.tag(),
                    topic ? ReviewTaxonomyCatalog.topicLabel(tag.tag()) : ReviewTaxonomyCatalog.lossLabel(tag.tag()),
                    clampConfidence(tag.confidence()), truncate(tag.evidence(), 1000)));
        }
        return tags;
    }

    private static ReviewSummaryResponse normalizeSummary(ReviewSummaryResponse summary) {
        return new ReviewSummaryResponse(truncate(summary.overall(), 2000),
                list(summary.strengths(), 500), list(summary.improvements(), 500),
                list(summary.nextPreparation(), 500));
    }

    private static List<ImprovementActionDraftResponse> normalizeActions(List<ImprovementActionDraftResponse> input) {
        List<ImprovementActionDraftResponse> actions = new ArrayList<>();
        for (ImprovementActionDraftResponse action : input) {
            if (action == null || blank(action.action()) || actions.size() >= 3) {
                continue;
            }
            actions.add(new ImprovementActionDraftResponse(truncate(action.action(), 500),
                    truncate(action.category(), 64), truncate(action.reason(), 1000)));
        }
        return actions;
    }

    private static List<ReviewTagResponse> tags(List<AiReviewAnalysis.Tag> input, boolean topic) {
        List<ReviewTagResponse> tags = new ArrayList<>();
        if (input == null) {
            return tags;
        }
        Set<String> seen = new java.util.HashSet<>();
        for (AiReviewAnalysis.Tag tag : input) {
            if (tag == null || blank(tag.tag())) {
                continue;
            }
            String key = tag.tag().trim();
            boolean valid = topic ? ReviewTaxonomyCatalog.isTopic(key) : ReviewTaxonomyCatalog.isLossTag(key);
            if (!valid || !seen.add(key)) {
                continue;
            }
            tags.add(new ReviewTagResponse(key,
                    topic ? ReviewTaxonomyCatalog.topicLabel(key) : ReviewTaxonomyCatalog.lossLabel(key),
                    clampConfidence(tag.confidence()), truncate(tag.evidence(), 1000)));
        }
        return tags;
    }

    private static ReviewSummaryResponse summary(AiReviewAnalysis.Summary summary) {
        if (summary == null) {
            return new ReviewSummaryResponse(null, List.of(), List.of(), List.of());
        }
        return new ReviewSummaryResponse(truncate(summary.overall(), 2000),
                list(summary.strengths(), 500), list(summary.improvements(), 500),
                list(summary.nextPreparation(), 500));
    }

    private static List<ImprovementActionDraftResponse> drafts(List<AiReviewAnalysis.Action> input) {
        List<ImprovementActionDraftResponse> actions = new ArrayList<>();
        if (input == null) {
            return actions;
        }
        for (AiReviewAnalysis.Action action : input) {
            if (action == null || blank(action.action()) || actions.size() >= 3) {
                continue;
            }
            actions.add(new ImprovementActionDraftResponse(truncate(action.action(), 500),
                    truncate(action.category(), 64), truncate(action.reason(), 1000)));
        }
        return actions;
    }

    private static List<String> topicKeys(List<String> input) {
        return checkedKeys(input, true);
    }

    private static List<String> lossKeys(List<String> input) {
        return checkedKeys(input, false);
    }

    private static List<String> checkedKeys(List<String> input, boolean topic) {
        if (input == null) {
            return List.of();
        }
        return input.stream().filter(key -> topic ? ReviewTaxonomyCatalog.isTopic(key)
                : ReviewTaxonomyCatalog.isLossTag(key)).distinct().toList();
    }

    /** 用户修正的标签必须落在受控词表内，未知 key 直接 400。 */
    private static List<String> validatedKeys(List<String> input, boolean topic) {
        if (input == null) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        for (String key : input) {
            String value = key == null ? null : key.trim();
            boolean valid = topic ? ReviewTaxonomyCatalog.isTopic(value) : ReviewTaxonomyCatalog.isLossTag(value);
            if (!valid) {
                throw BizException.of(ErrorCode.BAD_REQUEST,
                        (topic ? "知识点标签" : "失分类型") + "不在受控词表内: " + key);
            }
            if (!keys.contains(value)) {
                keys.add(value);
            }
        }
        return keys;
    }

    private static List<String> list(List<String> input, int maxLength) {
        if (input == null) {
            return List.of();
        }
        return input.stream().filter(item -> !blank(item)).map(item -> truncate(item, maxLength))
                .limit(10).toList();
    }

    private ReviewSession require(Long reviewSessionId) {
        ReviewSession session = sessionMapper.selectById(reviewSessionId);
        if (session == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "复盘记录不存在: " + reviewSessionId);
        }
        return session;
    }

    /** REVIEW_ANALYZE 场景绑定优先，未绑定时回落全局默认。 */
    private LlmSceneService.SceneModel resolveModel() {
        LlmSceneService.SceneModel bound = sceneService.optionalResolve(LlmScene.REVIEW_ANALYZE);
        if (bound != null) {
            return bound;
        }
        LlmSceneService.SceneModel global = sceneService.resolveGlobal();
        if (global == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST,
                    "尚未设置默认模型：请打开左下角「设置 → 模型服务」，设置全局默认或场景绑定后再试");
        }
        return global;
    }

    private boolean hasDefaultModel() {
        if (sceneService.optionalResolve(LlmScene.REVIEW_ANALYZE) != null) {
            return true;
        }
        try {
            return sceneService.resolveGlobal() != null;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private void requireDefaultModel() {
        resolveModel();
    }

    private static <T> Map<Long, T> lookup(List<Long> ids, Function<List<Long>, List<T>> loader,
                                           Function<T, Long> idOf) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return loader.apply(ids).stream().collect(Collectors.toMap(idOf, Function.identity(), (a, b) -> a));
    }

    private static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化复盘分析失败", ex);
        }
    }

    private static String enumNode(JsonNode node, Set<String> allowed, String field) {
        if (node.isNull()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, field + " 不能为空");
        }
        String value = node.asText().trim();
        if (!allowed.contains(value)) {
            throw BizException.of(ErrorCode.BAD_REQUEST, field + " 取值非法");
        }
        return value;
    }

    private static OffsetDateTime parseTime(JsonNode node) {
        if (node.isNull()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "面试时间不能为空");
        }
        try {
            return OffsetDateTime.parse(node.asText());
        } catch (DateTimeParseException ex) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "面试时间格式非法，需为 ISO-8601");
        }
    }

    private static String nodeText(JsonNode node, int max) {
        return node.isNull() ? null : text(node.asText(), max);
    }

    private static String text(String value, int max) {
        if (blank(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static String truncate(String value, int max) {
        if (blank(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static Integer clamp(Integer score) {
        return score == null ? null : Math.max(0, Math.min(100, score));
    }

    private static Double clampConfidence(Double confidence) {
        return confidence == null ? null : Math.max(0, Math.min(1, confidence));
    }

    private static int count(Integer value) {
        return value == null ? 0 : value;
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
