package com.jobpilot.modules.review.dto;

import java.time.OffsetDateTime;

/** 真实面试轮次（契约 InterviewEventListItem）；公司与岗位展示字段由服务层补齐。 */
public record InterviewEventListItemResponse(
        Long id,
        Long applicationId,
        Long companyId,
        String companyName,
        Long jobPositionId,
        String jobTitle,
        Integer roundNo,
        String roundType,
        String interviewerRole,
        String format,
        OffsetDateTime interviewTime,
        String result,
        String notes,
        Long reviewSessionId,
        String reviewStatus,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
