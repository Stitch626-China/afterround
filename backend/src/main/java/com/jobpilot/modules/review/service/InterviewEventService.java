package com.jobpilot.modules.review.service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.api.PageResult;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.modules.application.entity.JobApplication;
import com.jobpilot.modules.application.mapper.JobApplicationMapper;
import com.jobpilot.modules.company.entity.Company;
import com.jobpilot.modules.company.mapper.CompanyMapper;
import com.jobpilot.modules.job.entity.JobPosition;
import com.jobpilot.modules.job.mapper.JobPositionMapper;
import com.jobpilot.modules.review.dto.CreateInterviewEventRequest;
import com.jobpilot.modules.review.dto.InterviewEventListItemResponse;
import com.jobpilot.modules.review.dto.UpdateInterviewEventRequest;
import com.jobpilot.modules.review.entity.InterviewEvent;
import com.jobpilot.modules.review.entity.ReviewSession;
import com.jobpilot.modules.review.mapper.InterviewEventMapper;
import com.jobpilot.modules.review.mapper.ReviewSessionMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 真实面试轮次（F5.1）：按投递记录归集，同一投递同一轮次唯一。
 *
 * <p>只给岗位时优先复用该岗位最近一条投递，没有则自动创建为「面试中」；
 * 删除事件前必须先删除关联复盘（FR4.9）。
 */
@Service
public class InterviewEventService {

    private static final Set<String> ROUND_TYPES = Set.of("HR", "TECH", "CROSS", "DIRECTOR", "FINAL", "OTHER");
    private static final Set<String> FORMATS = Set.of("PHONE", "VIDEO", "ONSITE", "OTHER");
    private static final Set<String> RESULTS = Set.of("PASS", "FAIL", "PENDING");

    private final InterviewEventMapper eventMapper;
    private final ReviewSessionMapper reviewSessionMapper;
    private final JobApplicationMapper applicationMapper;
    private final JobPositionMapper jobMapper;
    private final CompanyMapper companyMapper;

    public InterviewEventService(InterviewEventMapper eventMapper, ReviewSessionMapper reviewSessionMapper,
                                 JobApplicationMapper applicationMapper, JobPositionMapper jobMapper,
                                 CompanyMapper companyMapper) {
        this.eventMapper = eventMapper;
        this.reviewSessionMapper = reviewSessionMapper;
        this.applicationMapper = applicationMapper;
        this.jobMapper = jobMapper;
        this.companyMapper = companyMapper;
    }

    /** 面试事件统计（投递列表用）：事件数与最新轮次号。 */
    public record InterviewStats(int count, Integer latestRoundNo) {
    }

    public PageResult<InterviewEventListItemResponse> page(Integer page, Integer size, Long applicationId,
                                                            String result, String roundType) {
        int current = PageResult.normalizePage(page);
        int pageSize = PageResult.normalizeSize(size);
        Page<InterviewEvent> rows = eventMapper.selectPage(new Page<>(current, pageSize),
                Wrappers.<InterviewEvent>lambdaQuery()
                        .eq(applicationId != null, InterviewEvent::getApplicationId, applicationId)
                        .eq(!blank(result), InterviewEvent::getResult, result)
                        .eq(!blank(roundType), InterviewEvent::getRoundType, roundType)
                        .orderByDesc(InterviewEvent::getInterviewTime).orderByDesc(InterviewEvent::getId));
        return PageResult.of(toItems(rows.getRecords()), rows.getTotal(), current, pageSize);
    }

    public InterviewEventListItemResponse create(CreateInterviewEventRequest request) {
        String roundType = enumValue(request.roundType(), ROUND_TYPES, "roundType", true);
        String format = enumValue(request.format(), FORMATS, "format", false);
        String result = request.result() == null ? "PENDING" : enumValue(request.result(), RESULTS, "result", true);
        JobApplication application = resolveApplication(request.applicationId(), request.jobPositionId());
        Long duplicate = eventMapper.selectCount(Wrappers.<InterviewEvent>lambdaQuery()
                .eq(InterviewEvent::getApplicationId, application.getId())
                .eq(InterviewEvent::getRoundNo, request.roundNo()));
        if (duplicate != null && duplicate > 0) {
            throw BizException.of(ErrorCode.REVIEW_ROUND_EXISTS, "该投递的第 " + request.roundNo() + " 轮面试已存在");
        }
        OffsetDateTime now = OffsetDateTime.now();
        InterviewEvent entity = new InterviewEvent();
        entity.setUserId(1L);
        entity.setApplicationId(application.getId());
        entity.setRoundNo(request.roundNo());
        entity.setRoundType(roundType);
        entity.setInterviewerRole(text(request.interviewerRole(), 64));
        entity.setFormat(format);
        entity.setInterviewTime(request.interviewTime() == null ? now : request.interviewTime());
        entity.setResult(result);
        entity.setNotes(text(request.notes(), 1000));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        try {
            eventMapper.insert(entity);
        } catch (DuplicateKeyException ex) {
            throw BizException.of(ErrorCode.REVIEW_ROUND_EXISTS, "该投递的第 " + request.roundNo() + " 轮面试已存在");
        }
        return toItems(List.of(entity)).get(0);
    }

    /**
     * 复盘创建时复用或新建面试轮次：已有轮次只补非空上下文，不覆盖用户已填内容。
     */
    public InterviewEvent ensureEvent(Long applicationId, Long jobPositionId, Integer roundNo, String roundType,
                                      String interviewerRole, String format, OffsetDateTime interviewTime) {
        if (roundNo == null || roundNo < 1) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "roundNo 至少为 1");
        }
        String type = enumValue(roundType, ROUND_TYPES, "roundType", true);
        String fmt = enumValue(format, FORMATS, "format", false);
        JobApplication application = resolveApplication(applicationId, jobPositionId);
        InterviewEvent existing = eventMapper.selectOne(Wrappers.<InterviewEvent>lambdaQuery()
                .eq(InterviewEvent::getApplicationId, application.getId())
                .eq(InterviewEvent::getRoundNo, roundNo));
        if (existing != null) {
            boolean dirty = false;
            if (!blank(interviewerRole) && !interviewerRole.trim().equals(existing.getInterviewerRole())) {
                existing.setInterviewerRole(text(interviewerRole, 64));
                dirty = true;
            }
            if (fmt != null && !fmt.equals(existing.getFormat())) {
                existing.setFormat(fmt);
                dirty = true;
            }
            if (interviewTime != null && !interviewTime.equals(existing.getInterviewTime())) {
                existing.setInterviewTime(interviewTime);
                dirty = true;
            }
            if (dirty) {
                existing.setUpdatedAt(OffsetDateTime.now());
                eventMapper.updateById(existing);
            }
            return existing;
        }
        OffsetDateTime now = OffsetDateTime.now();
        InterviewEvent entity = new InterviewEvent();
        entity.setUserId(1L);
        entity.setApplicationId(application.getId());
        entity.setRoundNo(roundNo);
        entity.setRoundType(type);
        entity.setInterviewerRole(text(interviewerRole, 64));
        entity.setFormat(fmt);
        entity.setInterviewTime(interviewTime == null ? now : interviewTime);
        entity.setResult("PENDING");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        try {
            eventMapper.insert(entity);
            return entity;
        } catch (DuplicateKeyException ex) {
            return eventMapper.selectOne(Wrappers.<InterviewEvent>lambdaQuery()
                    .eq(InterviewEvent::getApplicationId, application.getId())
                    .eq(InterviewEvent::getRoundNo, roundNo));
        }
    }

    public InterviewEventListItemResponse update(Long eventId, UpdateInterviewEventRequest request) {
        InterviewEvent entity = require(eventId);
        if (request.roundType() != null) {
            entity.setRoundType(enumNode(request.roundType(), ROUND_TYPES, "roundType", true));
        }
        if (request.interviewerRole() != null) {
            entity.setInterviewerRole(nodeText(request.interviewerRole(), 64));
        }
        if (request.format() != null) {
            entity.setFormat(enumNode(request.format(), FORMATS, "format", false));
        }
        if (request.interviewTime() != null) {
            entity.setInterviewTime(parseTime(request.interviewTime()));
        }
        if (request.result() != null) {
            entity.setResult(enumNode(request.result(), RESULTS, "result", true));
        }
        if (request.notes() != null) {
            entity.setNotes(nodeText(request.notes(), 1000));
        }
        entity.setUpdatedAt(OffsetDateTime.now());
        eventMapper.updateById(entity);
        return toItems(List.of(entity)).get(0);
    }

    /** 硬删除；存在复盘时返回 409，避免复盘失去面试上下文。 */
    public void delete(Long eventId) {
        require(eventId);
        Long reviews = reviewSessionMapper.selectCount(Wrappers.<ReviewSession>lambdaQuery()
                .eq(ReviewSession::getEventId, eventId));
        if (reviews != null && reviews > 0) {
            throw BizException.of(ErrorCode.RESOURCE_REFERENCED, "存在关联复盘记录，请先删除复盘");
        }
        eventMapper.deleteById(eventId);
    }

    /** 投递时间线：按轮次升序还原面试进程。 */
    public List<InterviewEventListItemResponse> listByApplication(Long applicationId) {
        return toItems(eventMapper.selectList(Wrappers.<InterviewEvent>lambdaQuery()
                .eq(InterviewEvent::getApplicationId, applicationId)
                .orderByAsc(InterviewEvent::getRoundNo)));
    }

    public boolean existsByApplication(Long applicationId) {
        Long count = eventMapper.selectCount(Wrappers.<InterviewEvent>lambdaQuery()
                .eq(InterviewEvent::getApplicationId, applicationId));
        return count != null && count > 0;
    }

    public Map<Long, InterviewStats> stats(List<Long> applicationIds) {
        if (applicationIds.isEmpty()) {
            return Map.of();
        }
        return eventMapper.selectList(Wrappers.<InterviewEvent>lambdaQuery()
                        .in(InterviewEvent::getApplicationId, applicationIds))
                .stream()
                .collect(Collectors.groupingBy(InterviewEvent::getApplicationId))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> new InterviewStats(
                        entry.getValue().size(),
                        entry.getValue().stream().map(InterviewEvent::getRoundNo).max(Integer::compareTo)
                                .orElse(null))));
    }

    /** applicationId 优先；仅给岗位时复用最近一条投递，没有则创建为「面试中」。 */
    public JobApplication resolveApplication(Long applicationId, Long jobPositionId) {
        if (applicationId != null) {
            JobApplication application = applicationMapper.selectById(applicationId);
            if (application == null) {
                throw BizException.of(ErrorCode.NOT_FOUND, "投递记录不存在: " + applicationId);
            }
            return application;
        }
        if (jobPositionId == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "applicationId 与 jobPositionId 至少提供一个");
        }
        JobPosition job = jobMapper.selectById(jobPositionId);
        if (job == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "岗位不存在: " + jobPositionId);
        }
        List<JobApplication> existing = applicationMapper.selectList(Wrappers.<JobApplication>lambdaQuery()
                .eq(JobApplication::getJobPositionId, jobPositionId)
                .orderByDesc(JobApplication::getAppliedAt).orderByDesc(JobApplication::getId)
                .last("limit 1"));
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        OffsetDateTime now = OffsetDateTime.now();
        JobApplication created = new JobApplication();
        created.setUserId(1L);
        created.setJobPositionId(jobPositionId);
        created.setAppliedAt(now);
        created.setStatus("INTERVIEWING");
        created.setCreatedAt(now);
        created.setUpdatedAt(now);
        applicationMapper.insert(created);
        if (!atLeastInterviewing(job.getStatus())) {
            jobMapper.update(null, Wrappers.<JobPosition>lambdaUpdate()
                    .eq(JobPosition::getId, job.getId())
                    .set(JobPosition::getStatus, "INTERVIEWING")
                    .set(JobPosition::getUpdatedAt, now));
        }
        return created;
    }

    private List<InterviewEventListItemResponse> toItems(List<InterviewEvent> events) {
        if (events.isEmpty()) {
            return List.of();
        }
        Map<Long, JobApplication> applications = lookup(events.stream()
                        .map(InterviewEvent::getApplicationId).filter(Objects::nonNull).distinct().toList(),
                applicationMapper::selectByIds, JobApplication::getId);
        Map<Long, JobPosition> jobs = lookup(applications.values().stream()
                        .map(JobApplication::getJobPositionId).filter(Objects::nonNull).distinct().toList(),
                jobMapper::selectByIds, JobPosition::getId);
        Map<Long, Company> companies = lookup(jobs.values().stream()
                        .map(JobPosition::getCompanyId).filter(Objects::nonNull).distinct().toList(),
                companyMapper::selectByIds, Company::getId);
        Map<Long, ReviewSession> reviews = reviewSessionMapper.selectList(Wrappers.<ReviewSession>lambdaQuery()
                        .in(ReviewSession::getEventId, events.stream().map(InterviewEvent::getId).toList()))
                .stream().collect(Collectors.toMap(ReviewSession::getEventId, Function.identity(), (a, b) -> a));
        return events.stream().map(event -> {
            JobApplication application = applications.get(event.getApplicationId());
            JobPosition job = application == null ? null : jobs.get(application.getJobPositionId());
            Company company = job == null || job.getCompanyId() == null ? null : companies.get(job.getCompanyId());
            ReviewSession review = reviews.get(event.getId());
            return new InterviewEventListItemResponse(event.getId(), event.getApplicationId(),
                    job == null ? null : job.getCompanyId(), company == null ? null : company.getName(),
                    job == null ? null : job.getId(), job == null ? null : job.getTitle(),
                    event.getRoundNo(), event.getRoundType(), event.getInterviewerRole(), event.getFormat(),
                    event.getInterviewTime(), event.getResult(), event.getNotes(),
                    review == null ? null : review.getId(), review == null ? null : review.getStatus(),
                    event.getCreatedAt(), event.getUpdatedAt());
        }).toList();
    }

    private static <T> Map<Long, T> lookup(List<Long> ids, Function<List<Long>, List<T>> loader,
                                           Function<T, Long> idOf) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return loader.apply(ids).stream().collect(Collectors.toMap(idOf, Function.identity(), (a, b) -> a));
    }

    private InterviewEvent require(Long eventId) {
        InterviewEvent entity = eventMapper.selectById(eventId);
        if (entity == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "面试事件不存在: " + eventId);
        }
        return entity;
    }

    private static boolean atLeastInterviewing(String jobStatus) {
        return "INTERVIEWING".equals(jobStatus) || "OFFERED".equals(jobStatus) || "REJECTED".equals(jobStatus);
    }

    private static String enumValue(String value, Set<String> allowed, String field, boolean required) {
        if (blank(value)) {
            if (required) {
                throw BizException.of(ErrorCode.BAD_REQUEST, field + " 不能为空");
            }
            return null;
        }
        String trimmed = value.trim();
        if (!allowed.contains(trimmed)) {
            throw BizException.of(ErrorCode.BAD_REQUEST, field + " 取值非法");
        }
        return trimmed;
    }

    private static String enumNode(JsonNode node, Set<String> allowed, String field, boolean required) {
        if (node.isNull()) {
            if (required) {
                throw BizException.of(ErrorCode.BAD_REQUEST, field + " 不能为空");
            }
            return null;
        }
        return enumValue(node.asText(), allowed, field, required);
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

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
