package com.jobpilot.modules.review.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 手动新增改进行动（契约 CreateImprovementActionRequest）。 */
public record CreateImprovementActionRequest(
        @NotBlank @Size(max = 500) String action,
        @Size(max = 64) String category,
        OffsetDateTime dueAt,
        Long sourceReviewId,
        Long sourceEventId) {
}
