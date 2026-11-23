package com.jobpilot.modules.review.dto;

/** 单题维度评分（契约 ReviewDimensionScore）；dimension 来自受控词表。 */
public record ReviewDimensionScoreResponse(
        String dimension,
        String label,
        Integer score) {
}
