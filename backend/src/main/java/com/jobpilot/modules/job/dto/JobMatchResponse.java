package com.jobpilot.modules.job.dto;

import java.time.OffsetDateTime;

/** 匹配结果（契约 JobMatch）：状态轮询与列表共用。 */
public record JobMatchResponse(
        Long id,
        Long resumeId,
        Long jobPositionId,
        String jobPositionTitle,
        String companyName,
        String status,
        String error,
        Integer retryCount,
        Integer overallScore,
        JobMatchDetailResponse detail,
        String llmProvider,
        String llmModel,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
