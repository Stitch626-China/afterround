package com.jobpilot.modules.review.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** 复盘受控词表（契约 ReviewTaxonomy）：前端选择器与洞察使用同一版本口径。 */
public record ReviewTaxonomyResponse(
        List<TaxonomyItemResponse> topics,
        List<TaxonomyItemResponse> lossTags,
        List<TaxonomyDimensionResponse> dimensions,
        String taxonomyVersion,
        String rubricVersion,
        OffsetDateTime updatedAt) {
}
