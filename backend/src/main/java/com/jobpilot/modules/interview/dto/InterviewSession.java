package com.jobpilot.modules.interview.dto;

import java.time.OffsetDateTime;

/** 模拟面试会话摘要（FR3.8）。 */
public record InterviewSession(
        Long id,
        String direction,
        String directionLabel,
        Long resumeId,
        String resumeFileName,
        Long jobPositionId,
        String jobPositionTitle,
        String companyName,
        String status,
        String evaluateStatus,
        Integer questionCount,
        Integer followUpCount,
        Integer answeredCount,
        String currentProviderKey,
        String currentModel,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
