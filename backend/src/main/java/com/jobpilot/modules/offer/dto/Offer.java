package com.jobpilot.modules.offer.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** Offer 摘要（FR6.2 / FR6.3）；社交与公积金拆成基数 / 比例两个字段。 */
public record Offer(
        Long id,
        Long companyId,
        String companyName,
        Long jobPositionId,
        Long applicationId,
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
        OffsetDateTime updatedAt) {
}
