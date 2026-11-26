package com.jobpilot.modules.application.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 记录一次投递（契约 CreateJobApplicationRequest）；appliedAt 为空取当前时间，status 为空默认 APPLIED。 */
public record CreateJobApplicationRequest(
        @NotNull Long jobPositionId,
        Long resumeId,
        @Size(max = 64) String channel,
        OffsetDateTime appliedAt,
        String status,
        @Size(max = 500) String outcome,
        @Size(max = 2000) String notes) {
}
