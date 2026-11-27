package com.jobpilot.modules.eval.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** 评测运行摘要。 */
public record EvalRunSummary(
        Long id,
        String status,
        List<String> scenes,
        int totalCases,
        int completedCases,
        int errorCases,
        String error,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt) {
}
