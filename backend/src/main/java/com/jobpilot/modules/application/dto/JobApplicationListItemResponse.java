package com.jobpilot.modules.application.dto;

import java.time.OffsetDateTime;

/** 投递记录（契约 JobApplicationListItem）；冗余展示字段由服务层补齐。 */
public record JobApplicationListItemResponse(
        Long id,
        Long jobPositionId,
        String jobTitle,
        Long companyId,
        String companyName,
        Long resumeId,
        String resumeFileName,
        String channel,
        OffsetDateTime appliedAt,
        String status,
        String outcome,
        String notes,
        int interviewCount,
        Integer latestRoundNo,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
