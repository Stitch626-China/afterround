package com.jobpilot.modules.application.service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.api.PageResult;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.modules.application.dto.CreateJobApplicationRequest;
import com.jobpilot.modules.application.dto.JobApplicationListItemResponse;
import com.jobpilot.modules.application.dto.JobApplicationTimelineResponse;
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
import com.jobpilot.modules.review.service.InterviewEventService;
import org.springframework.stereotype.Service;

/**
 * 投递档案（F8）：一次投递关联岗位与可选简历版本；同一岗位允许多次投递，不做唯一约束。
 *
 * <p>状态向前同步岗位状态（只升不降，WITHDRAWN 不影响岗位）；删除受关联数据保护。
 */
@Service
public class JobApplicationService {

    private static final Set<String> STATUSES =
            Set.of("APPLIED", "INTERVIEWING", "OFFERED", "REJECTED", "WITHDRAWN");

    private final JobApplicationMapper applicationMapper;
    private final JobPositionMapper jobMapper;
    private final CompanyMapper companyMapper;
    private final ResumeMapper resumeMapper;
    private final OfferService offerService;
    private final InterviewEventService eventService;

    public JobApplicationService(JobApplicationMapper applicationMapper, JobPositionMapper jobMapper,
                                 CompanyMapper companyMapper, ResumeMapper resumeMapper,
                                 OfferService offerService, InterviewEventService eventService) {
        this.applicationMapper = applicationMapper;
        this.jobMapper = jobMapper;
        this.companyMapper = companyMapper;
        this.resumeMapper = resumeMapper;
        this.offerService = offerService;
        this.eventService = eventService;
    }

    public PageResult<JobApplicationListItemResponse> page(Integer page, Integer size, String status,
                                                            Long jobPositionId, Long resumeId, String keyword) {
        int current = PageResult.normalizePage(page);
        int pageSize = PageResult.normalizeSize(size);
        String kw = blank(keyword) ? null : keyword.trim();
        List<Long> jobIds = kw == null ? List.of() : jobIdsMatching(kw);
        if (kw != null && jobIds.isEmpty()) {
            return PageResult.of(List.of(), 0, current, pageSize);
        }
        Page<JobApplication> result = applicationMapper.selectPage(new Page<>(current, pageSize),
                Wrappers.<JobApplication>lambdaQuery()
                        .eq(!blank(status), JobApplication::getStatus, status)
                        .eq(jobPositionId != null, JobApplication::getJobPositionId, jobPositionId)
                        .eq(resumeId != null, JobApplication::getResumeId, resumeId)
                        .in(kw != null, JobApplication::getJobPositionId, jobIds)
                        .orderByDesc(JobApplication::getAppliedAt).orderByDesc(JobApplication::getId));
        return PageResult.of(toItems(result.getRecords()), result.getTotal(), current, pageSize);
    }

    /** 记录投递：岗位必须存在，简历可选；状态固定从 APPLIED 开始并同步岗位。 */
    public JobApplicationListItemResponse create(CreateJobApplicationRequest request) {
        JobPosition job = requireJob(request.jobPositionId());
        if (request.resumeId() != null) {
            requireResume(request.resumeId());
        }
        OffsetDateTime now = OffsetDateTime.now();
        JobApplication entity = new JobApplication();
        entity.setUserId(1L);
        entity.setJobPositionId(job.getId());
        entity.setResumeId(request.resumeId());
        entity.setChannel(text(request.channel(), 64));
        entity.setAppliedAt(request.appliedAt() == null ? now : request.appliedAt());
        entity.setStatus("APPLIED");
        entity.setNotes(text(request.notes(), 2000));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        applicationMapper.insert(entity);
        syncJobStatus(job, entity.getStatus());
        return toItems(List.of(entity)).get(0);
    }

    public JobApplicationListItemResponse update(Long applicationId, UpdateJobApplicationRequest request) {
        JobApplication entity = require(applicationId);
        JobPosition job = requireJob(entity.getJobPositionId());
        if (request.resumeId() != null) {
            entity.setResumeId(request.resumeId().isNull() ? null : requireResume(asLong(request.resumeId())));
        }
        if (request.channel() != null) {
            entity.setChannel(nodeText(request.channel(), 64));
        }
        if (request.appliedAt() != null) {
            entity.setAppliedAt(parseTime(request.appliedAt()));
        }
        if (request.status() != null) {
            String status = nodeText(request.status(), 16);
            if (status == null || !STATUSES.contains(status)) {
                throw BizException.of(ErrorCode.BAD_REQUEST, "status 取值非法");
            }
            entity.setStatus(status);
        }
        if (request.outcome() != null) {
            entity.setOutcome(nodeText(request.outcome(), 500));
        }
        if (request.notes() != null) {
            entity.setNotes(nodeText(request.notes(), 2000));
        }
        entity.setUpdatedAt(OffsetDateTime.now());
        applicationMapper.updateById(entity);
        syncJobStatus(job, entity.getStatus());
        return toItems(List.of(entity)).get(0);
    }

    /** 硬删除；存在面试事件时 409，Offer 通过外键置空 application_id，保留 Offer 数据。 */
    public void delete(Long applicationId) {
        require(applicationId);
        if (eventService.existsByApplication(applicationId)) {
            throw BizException.of(ErrorCode.RESOURCE_REFERENCED, "存在面试事件，请先删除面试事件与复盘");
        }
        applicationMapper.deleteById(applicationId);
    }

    /** 时间线：面试轮次按轮次升序；复盘与改进动作随 M3-b B2 / B3 接入，当前返回空数组。 */
    public JobApplicationTimelineResponse timeline(Long applicationId) {
        JobApplication entity = require(applicationId);
        return new JobApplicationTimelineResponse(toItems(List.of(entity)).get(0),
                eventService.listByApplication(applicationId), List.of(),
                offerService.findByApplicationId(applicationId), List.of());
    }

    /** 状态向前同步：SAVED < APPLIED < INTERVIEWING < OFFERED/REJECTED；WITHDRAWN 不改岗位。 */
    private void syncJobStatus(JobPosition job, String applicationStatus) {
        String target = switch (applicationStatus) {
            case "APPLIED" -> "APPLIED";
            case "INTERVIEWING" -> "INTERVIEWING";
            case "OFFERED" -> "OFFERED";
            case "REJECTED" -> "REJECTED";
            default -> null;
        };
        if (target == null || rank(target) <= rank(job.getStatus())) {
            return;
        }
        jobMapper.update(null, Wrappers.<JobPosition>lambdaUpdate()
                .eq(JobPosition::getId, job.getId())
                .set(JobPosition::getStatus, target)
                .set(JobPosition::getUpdatedAt, OffsetDateTime.now()));
    }

    private static int rank(String jobStatus) {
        return switch (jobStatus == null ? "" : jobStatus) {
            case "APPLIED" -> 1;
            case "INTERVIEWING" -> 2;
            case "OFFERED", "REJECTED" -> 3;
            default -> 0;
        };
    }

    private List<JobApplicationListItemResponse> toItems(List<JobApplication> applications) {
        if (applications.isEmpty()) {
            return List.of();
        }
        Map<Long, JobPosition> jobs = lookup(applications.stream()
                .map(JobApplication::getJobPositionId).distinct().toList(), jobMapper::selectByIds,
                JobPosition::getId);
        Map<Long, Company> companies = lookup(jobs.values().stream()
                        .map(JobPosition::getCompanyId).filter(java.util.Objects::nonNull).distinct().toList(),
                companyMapper::selectByIds, Company::getId);
        Map<Long, Resume> resumes = lookup(applications.stream()
                        .map(JobApplication::getResumeId).filter(java.util.Objects::nonNull).distinct().toList(),
                resumeMapper::selectByIds, Resume::getId);
        Map<Long, InterviewEventService.InterviewStats> stats = eventService.stats(applications.stream()
                .map(JobApplication::getId).toList());
        return applications.stream().map(application -> {
            JobPosition job = jobs.get(application.getJobPositionId());
            Company company = job == null || job.getCompanyId() == null ? null : companies.get(job.getCompanyId());
            Resume resume = application.getResumeId() == null ? null : resumes.get(application.getResumeId());
            InterviewEventService.InterviewStats eventStats = stats.get(application.getId());
            return new JobApplicationListItemResponse(application.getId(), application.getJobPositionId(),
                    job == null ? null : job.getTitle(), job == null ? null : job.getCompanyId(),
                    company == null ? null : company.getName(), application.getResumeId(),
                    resume == null ? null : resume.getFileName(), application.getChannel(),
                    application.getAppliedAt(), application.getStatus(), application.getOutcome(),
                    application.getNotes(), eventStats == null ? 0 : eventStats.count(),
                    eventStats == null ? null : eventStats.latestRoundNo(),
                    application.getCreatedAt(), application.getUpdatedAt());
        }).toList();
    }

    private static <T> Map<Long, T> lookup(List<Long> ids, Function<List<Long>, List<T>> loader,
                                           Function<T, Long> idOf) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return loader.apply(ids).stream().collect(Collectors.toMap(idOf, Function.identity()));
    }

    /** 关键字命中公司名或岗位名时，先取出候选岗位 id。 */
    private List<Long> jobIdsMatching(String keyword) {
        List<Long> companyIds = companyMapper.selectList(Wrappers.<Company>lambdaQuery()
                        .like(Company::getName, keyword))
                .stream().map(Company::getId).toList();
        return jobMapper.selectList(Wrappers.<JobPosition>lambdaQuery()
                        .like(JobPosition::getTitle, keyword)
                        .or(!companyIds.isEmpty(), w -> w.in(JobPosition::getCompanyId, companyIds)))
                .stream().map(JobPosition::getId).toList();
    }

    private JobPosition requireJob(Long jobPositionId) {
        JobPosition job = jobMapper.selectById(jobPositionId);
        if (job == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "岗位不存在: " + jobPositionId);
        }
        return job;
    }

    private Long requireResume(Long resumeId) {
        if (resumeMapper.selectById(resumeId) == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "简历不存在: " + resumeId);
        }
        return resumeId;
    }

    private JobApplication require(Long applicationId) {
        JobApplication entity = applicationMapper.selectById(applicationId);
        if (entity == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "投递记录不存在: " + applicationId);
        }
        return entity;
    }

    private static OffsetDateTime parseTime(JsonNode node) {
        if (node.isNull()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "投递时间不能为空");
        }
        try {
            return OffsetDateTime.parse(node.asText());
        } catch (DateTimeParseException ex) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "投递时间格式非法，需为 ISO-8601");
        }
    }

    private static Long asLong(JsonNode node) {
        if (!node.isNumber()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "resumeId 必须为数字");
        }
        return node.asLong();
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
