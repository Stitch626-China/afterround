package com.jobpilot.modules.offer.ai;

import java.math.BigDecimal;
import java.util.List;

/** Offer 提取的 AI 中间 DTO：与业务实体分离，落库前逐项校验。 */
public record AiOfferExtraction(
        String companyName,
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
        String remark) {
}
