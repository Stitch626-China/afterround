package com.jobpilot.modules.interview.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** 统一评估报告（FR3.6）；未完成时报告字段为空，逐题得分在会话详情中。 */
public record InterviewEvaluation(
        String status,
        String error,
        Integer retryCount,
        Integer overallScore,
        List<InterviewEvaluationCategory> categories,
        List<String> strengths,
        List<String> improvements,
        String summary,
        String llmProvider,
        String llmModel,
        OffsetDateTime evaluatedAt) {
}
