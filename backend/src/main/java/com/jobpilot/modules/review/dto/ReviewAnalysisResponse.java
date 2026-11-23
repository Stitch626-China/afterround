package com.jobpilot.modules.review.dto;

import java.util.List;

/** 结构化复盘分析（契约 ReviewAnalysis）：保存时作为整体 JSON 落库。 */
public record ReviewAnalysisResponse(
        List<ReviewQuestionResponse> questions,
        List<ReviewTagResponse> topics,
        List<ReviewTagResponse> lossTags,
        ReviewSummaryResponse summary,
        List<ImprovementActionDraftResponse> actions,
        String promptVersion,
        String rubricVersion,
        String taxonomyVersion,
        boolean confirmed,
        boolean degraded,
        String llmProvider,
        String llmModel) {
}
