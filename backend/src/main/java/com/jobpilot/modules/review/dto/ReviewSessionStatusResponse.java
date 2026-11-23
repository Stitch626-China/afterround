package com.jobpilot.modules.review.dto;

import java.time.OffsetDateTime;

/** 复盘轻量状态（契约 ReviewSessionStatus）：只返回状态、错误与重试次数。 */
public record ReviewSessionStatusResponse(
        Long id,
        String status,
        String asrStatus,
        String analyzeStatus,
        int asrRetryCount,
        int analyzeRetryCount,
        String asrError,
        String analyzeError,
        OffsetDateTime updatedAt) {
}
