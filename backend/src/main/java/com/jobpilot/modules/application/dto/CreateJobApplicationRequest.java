package com.jobpilot.modules.application.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 记录一次投递（契约 CreateJobApplicationRequest）；appliedAt 为空取当前时间。 */
public record CreateJobApplicationRequest(
        @NotNull Long jobPositionId,
        Long resumeId,
        @Size(max = 64) String channel,
        OffsetDateTime appliedAt,
        @Size(max = 2000) String notes) {
}
