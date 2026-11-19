package com.jobpilot.modules.offer.dto;

import java.math.BigDecimal;
import java.util.List;

/** 从文本 / 图片提取的 Offer 草稿（FR6.1）：所有字段可能为 null，由用户修正后保存。 */
public record OfferExtraction(
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
