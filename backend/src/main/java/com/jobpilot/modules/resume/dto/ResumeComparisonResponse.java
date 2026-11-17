package com.jobpilot.modules.resume.dto;

import java.util.List;

/** 简历版本对比（契约 ResumeComparison）：分数与差异本地计算，summary 由 LLM 生成。 */
public record ResumeComparisonResponse(
        ComparisonSideResponse base,
        ComparisonSideResponse target,
        Integer overallScoreDelta,
        List<DimensionDeltaResponse> dimensionDeltas,
        List<QuoteItemResponse> strengthsAdded,
        List<QuoteItemResponse> strengthsRemoved,
        List<QuoteItemResponse> weaknessesAdded,
        List<QuoteItemResponse> weaknessesRemoved,
        List<String> directionsAdded,
        List<String> directionsRemoved,
        ComparisonSummaryResponse summary) {
}
