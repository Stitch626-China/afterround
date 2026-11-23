package com.jobpilot.modules.review.dto;

import java.util.List;

/** 单题复盘（契约 ReviewQuestion）：问题、回答摘要、评分、标签与证据。 */
public record ReviewQuestionResponse(
        Integer questionIndex,
        String question,
        String answerSummary,
        Integer score,
        List<ReviewDimensionScoreResponse> dimensions,
        List<String> topics,
        List<String> lossTags,
        String evidence,
        String feedback,
        String referenceAnswer) {
}
