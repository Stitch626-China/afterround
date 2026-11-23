package com.jobpilot.modules.review.service;

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
import com.jobpilot.modules.review.dto.CreateImprovementActionRequest;
import com.jobpilot.modules.review.dto.ImprovementActionDraftResponse;
import com.jobpilot.modules.review.dto.ImprovementActionResponse;
import com.jobpilot.modules.review.dto.UpdateImprovementActionRequest;
import com.jobpilot.modules.review.entity.ImprovementAction;
import com.jobpilot.modules.review.entity.InterviewEvent;
import com.jobpilot.modules.review.entity.ReviewSession;
import com.jobpilot.modules.review.mapper.ImprovementActionMapper;
import com.jobpilot.modules.review.mapper.InterviewEventMapper;
import com.jobpilot.modules.review.mapper.ReviewSessionMapper;
import org.springframework.stereotype.Service;

/**
 * 改进动作（FR4.4 / FR5.6）：复盘确认时按草稿创建或更新，也支持手动新增；
 * 状态流转 TODO → IN_PROGRESS → VERIFIED / DROPPED，验证时可关联后续面试事件。
 */
@Service
public class ImprovementActionService {

    private static final Set<String> STATUSES = Set.of("TODO", "IN_PROGRESS", "VERIFIED", "DROPPED");

    private final ImprovementActionMapper actionMapper;
    private final ReviewSessionMapper reviewSessionMapper;
    private final InterviewEventMapper eventMapper;

    public ImprovementActionService(ImprovementActionMapper actionMapper, ReviewSessionMapper reviewSessionMapper,
                                    InterviewEventMapper eventMapper) {
        this.actionMapper = actionMapper;
        this.reviewSessionMapper = reviewSessionMapper;
        this.eventMapper = eventMapper;
    }

    public PageResult<ImprovementActionResponse> page(Integer page, Integer size, String status,
                                                       Long sourceReviewId) {
        int current = PageResult.normalizePage(page);
        int pageSize = PageResult.normalizeSize(size);
        Page<ImprovementAction> rows = actionMapper.selectPage(new Page<>(current, pageSize),
                Wrappers.<ImprovementAction>lambdaQuery()
                        .eq(!blank(status), ImprovementAction::getStatus, status)
                        .eq(sourceReviewId != null, ImprovementAction::getSourceReviewId, sourceReviewId)
                        .orderByDesc(ImprovementAction::getCreatedAt).orderByDesc(ImprovementAction::getId));
        return PageResult.of(rows.getRecords().stream().map(this::toResponse).toList(),
                rows.getTotal(), current, pageSize);
    }

    public ImprovementActionResponse create(CreateImprovementActionRequest request) {
        Long applicationId = null;
        if (request.sourceReviewId() != null) {
            ReviewSession review = reviewSessionMapper.selectById(request.sourceReviewId());
            if (review == null) {
                throw BizException.of(ErrorCode.NOT_FOUND, "复盘记录不存在: " + request.sourceReviewId());
            }
            applicationId = applicationIdOfEvent(review.getEventId());
        }
        if (request.sourceEventId() != null) {
            InterviewEvent event = requireEvent(request.sourceEventId());
            if (applicationId == null) {
                applicationId = event.getApplicationId();
            }
        }
        OffsetDateTime now = OffsetDateTime.now();
        ImprovementAction entity = new ImprovementAction();
        entity.setUserId(1L);
        entity.setApplicationId(applicationId);
        entity.setSourceReviewId(request.sourceReviewId());
        entity.setSourceEventId(request.sourceEventId());
        entity.setAction(text(request.action(), 500));
        entity.setCategory(text(request.category(), 64));
        entity.setStatus("TODO");
        entity.setDueAt(request.dueAt());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        actionMapper.insert(entity);
        return toResponse(entity);
    }

    public ImprovementActionResponse update(Long actionId, UpdateImprovementActionRequest request) {
        ImprovementAction entity = require(actionId);
        if (request.action() != null) {
            String action = nodeText(request.action(), 500);
            if (action == null) {
                throw BizException.of(ErrorCode.BAD_REQUEST, "action 不能为空");
            }
            entity.setAction(action);
        }
        if (request.category() != null) {
            entity.setCategory(nodeText(request.category(), 64));
        }
        if (request.status() != null) {
            String status = nodeText(request.status(), 16);
            if (status == null || !STATUSES.contains(status)) {
                throw BizException.of(ErrorCode.BAD_REQUEST, "status 取值非法");
            }
            entity.setStatus(status);
        }
        if (request.dueAt() != null) {
            entity.setDueAt(nodeTime(request.dueAt()));
        }
        if (request.verifiedEventId() != null) {
            if (request.verifiedEventId().isNull()) {
                entity.setVerifiedEventId(null);
            } else {
                entity.setVerifiedEventId(requireEvent(request.verifiedEventId().asLong()).getId());
            }
        }
        if (request.evidence() != null) {
            entity.setEvidence(nodeText(request.evidence(), 2000));
        }
        entity.setUpdatedAt(OffsetDateTime.now());
        actionMapper.updateById(entity);
        return toResponse(entity);
    }

    public void delete(Long actionId) {
        require(actionId);
        actionMapper.deleteById(actionId);
    }

    /** 投递时间线：按创建时间倒序返回该投递下全部行动。 */
    public List<ImprovementActionResponse> listByApplication(Long applicationId) {
        return actionMapper.selectList(Wrappers.<ImprovementAction>lambdaQuery()
                        .eq(ImprovementAction::getApplicationId, applicationId)
                        .orderByDesc(ImprovementAction::getCreatedAt).orderByDesc(ImprovementAction::getId))
                .stream().map(this::toResponse).toList();
    }

    /**
     * 复盘确认时同步草稿：按行动文本复用已有行动（保留用户已设置的状态、截止时间与证据），
     * 不删除未出现在草稿中的行动，避免覆盖用户手动维护的数据。
     */
    public void syncFromDrafts(Long reviewSessionId, Long eventId, Long applicationId,
                               List<ImprovementActionDraftResponse> drafts) {
        List<ImprovementAction> existing = actionMapper.selectList(Wrappers.<ImprovementAction>lambdaQuery()
                .eq(ImprovementAction::getSourceReviewId, reviewSessionId));
        Map<String, ImprovementAction> byText = existing.stream()
                .collect(Collectors.toMap(ImprovementAction::getAction, Function.identity(), (a, b) -> a));
        OffsetDateTime now = OffsetDateTime.now();
        for (ImprovementActionDraftResponse draft : drafts) {
            String action = text(draft.action(), 500);
            if (action == null) {
                continue;
            }
            String category = text(draft.category(), 64);
            ImprovementAction found = byText.get(action);
            if (found != null) {
                if (category != null && !category.equals(found.getCategory())) {
                    found.setCategory(category);
                    found.setUpdatedAt(now);
                    actionMapper.updateById(found);
                }
                continue;
            }
            ImprovementAction entity = new ImprovementAction();
            entity.setUserId(1L);
            entity.setApplicationId(applicationId);
            entity.setSourceReviewId(reviewSessionId);
            entity.setSourceEventId(eventId);
            entity.setAction(action);
            entity.setCategory(category);
            entity.setStatus("TODO");
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            actionMapper.insert(entity);
        }
    }

    private Long applicationIdOfEvent(Long eventId) {
        InterviewEvent event = eventId == null ? null : eventMapper.selectById(eventId);
        return event == null ? null : event.getApplicationId();
    }

    private InterviewEvent requireEvent(Long eventId) {
        InterviewEvent event = eventMapper.selectById(eventId);
        if (event == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "面试事件不存在: " + eventId);
        }
        return event;
    }

    private ImprovementAction require(Long actionId) {
        ImprovementAction entity = actionMapper.selectById(actionId);
        if (entity == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "改进行动不存在: " + actionId);
        }
        return entity;
    }

    private ImprovementActionResponse toResponse(ImprovementAction entity) {
        return new ImprovementActionResponse(entity.getId(), entity.getSourceReviewId(), entity.getSourceEventId(),
                entity.getApplicationId(), entity.getAction(), entity.getCategory(), entity.getStatus(),
                entity.getDueAt(), entity.getVerifiedEventId(), entity.getEvidence(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private static OffsetDateTime nodeTime(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(node.asText());
        } catch (DateTimeParseException ex) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "时间格式非法，需为 ISO-8601");
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
