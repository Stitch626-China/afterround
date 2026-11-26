package com.jobpilot.modules.review.dto;

import java.time.OffsetDateTime;

/** 复盘记录（契约 ReviewSessionListItem）；公司与岗位展示字段由面试事件补齐。 */
public record ReviewSessionListItemResponse(
        Long id,
        Long interviewEventId,
        Long applicationId,
        String companyName,
        String jobTitle,
        Integer roundNo,
        String roundType,
        String inputType,
        String status,
        String asrStatus,
        String analyzeStatus,
        int asrRetryCount,
        int analyzeRetryCount,
        Integer audioDurationSeconds,
        String matchSnippet,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
