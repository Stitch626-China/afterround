package com.jobpilot.modules.resume.dto;

import jakarta.validation.constraints.NotNull;

/** 简历对比请求（契约 CreateResumeComparisonRequest）：两份都必须分析完成。 */
public record CreateResumeComparisonRequest(@NotNull Long baseResumeId, @NotNull Long targetResumeId) {
}
