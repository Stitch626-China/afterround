package com.jobpilot.modules.job.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** 行情洞察（契约 JobMarketInsights）：基于解析完成的 JD 实时计算，不落快照表。 */
public record JobMarketInsightsResponse(
        Integer sampleSize,
        OffsetDateTime generatedAt,
        List<SkillCountItemResponse> skillTop,
        List<CountItemResponse> salaryDistribution,
        List<CountItemResponse> experienceDistribution,
        List<CountItemResponse> directionDistribution) {
}
