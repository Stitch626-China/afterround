package com.jobpilot.modules.review.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 新建真实面试轮次（契约 CreateInterviewEventRequest）。
 * applicationId 与 jobPositionId 至少提供一个；仅给岗位时服务端复用或自动创建投递记录。
 */
public record CreateInterviewEventRequest(
        Long applicationId,
        Long jobPositionId,
        @NotNull @Min(1) Integer roundNo,
        @NotNull String roundType,
        @Size(max = 64) String interviewerRole,
        String format,
        OffsetDateTime interviewTime,
        String result,
        @Size(max = 1000) String notes) {
}
