package com.jobpilot.modules.resume.dto;

/** 对比总结（契约 ComparisonSummary）：LLM 失败时降级为 content = null。 */
public record ComparisonSummaryResponse(
        String content,
        Boolean modelMismatch,
        Boolean promptVersionMismatch,
        Boolean degraded,
        String degradeReason) {
}
