package com.jobpilot.modules.eval.dto;

import java.util.List;

/** 影响面提示：某场景当前配置与基线不一致，建议重建。 */
public record PromptImpactSuggestion(String scene, int caseCount, List<String> reasons, Long currentBaselineId) {
}
