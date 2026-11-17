package com.jobpilot.modules.job.dto;

import jakarta.validation.constraints.NotNull;

/** 发起匹配（契约 CreateJobMatchRequest）。 */
public record CreateJobMatchRequest(@NotNull Long resumeId, @NotNull Long jobPositionId) {
}
