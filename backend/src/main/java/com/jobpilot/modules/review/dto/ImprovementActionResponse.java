package com.jobpilot.modules.review.dto;

import java.time.OffsetDateTime;

/** 改进行动（契约 ImprovementAction）：来源复盘 / 面试事件与验证事件均可追溯。 */
public record ImprovementActionResponse(
        Long id,
        Long sourceReviewId,
        Long sourceEventId,
        Long applicationId,
        String action,
        String category,
        String status,
        OffsetDateTime dueAt,
        Long verifiedEventId,
        String evidence,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
