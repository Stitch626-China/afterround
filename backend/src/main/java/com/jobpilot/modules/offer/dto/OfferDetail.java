package com.jobpilot.modules.offer.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** Offer 详情：摘要 + 最近一次前景分析（未分析为 null）。 */
public record OfferDetail(
        Long id,
        Long companyId,
        String companyName,
        Long jobPositionId,
        String jobPositionTitle,
        Integer monthlySalary,
        Integer baseSalary,
        BigDecimal performanceRatio,
        String socialInsuranceBase,
        String socialInsuranceRatio,
        String housingFundBase,
        String housingFundRatio,
        Boolean twoDayWeekend,
        String workHours,
        List<String> benefits,
        List<String> allowances,
        String annualBonus,
        String quarterlyBonus,
        String remark,
        String group,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OfferAnalysis analysis) {
}
