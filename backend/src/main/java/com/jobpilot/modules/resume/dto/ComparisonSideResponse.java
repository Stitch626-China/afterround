package com.jobpilot.modules.resume.dto;

import java.time.OffsetDateTime;

/** 对比单侧信息（契约 ComparisonSide）。 */
public record ComparisonSideResponse(
        Long resumeId,
        String fileName,
        Integer overallScore,
        OffsetDateTime analyzedAt,
        String llmProvider,
        String llmModel,
        String promptVersion) {
}
