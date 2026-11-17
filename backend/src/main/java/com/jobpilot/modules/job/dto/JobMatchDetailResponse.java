package com.jobpilot.modules.job.dto;

import java.util.List;

/** 匹配详情（契约 JobMatchDetail）：仅 status = COMPLETED 时有值。 */
public record JobMatchDetailResponse(
        List<MatchDimensionResponse> dimensions,
        List<String> gaps,
        List<String> preparationAdvice) {
}
