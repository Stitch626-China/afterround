package com.jobpilot.modules.offer.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** Offer 前景与倾向分析（FR6.4 / FR6.5）。 */
public record OfferAnalysis(
        String tendency,
        List<String> reasons,
        String prospect,
        List<String> risks,
        List<String> sources,
        Boolean basedOnProvidedMaterials,
        String llmProvider,
        String llmModel,
        OffsetDateTime analyzedAt) {
}
