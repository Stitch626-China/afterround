package com.jobpilot.modules.review.dto;

/** 词表条目（契约 TaxonomyItem）。 */
public record TaxonomyItemResponse(
        String key,
        String label,
        String description) {
}
