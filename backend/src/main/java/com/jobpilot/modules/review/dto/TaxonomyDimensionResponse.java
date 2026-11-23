package com.jobpilot.modules.review.dto;

/** 评分维度（契约 TaxonomyDimension）：maxScore 固定 100。 */
public record TaxonomyDimensionResponse(
        String key,
        String label,
        String description,
        Integer maxScore) {
}
