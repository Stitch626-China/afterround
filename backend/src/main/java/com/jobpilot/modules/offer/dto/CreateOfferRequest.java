package com.jobpilot.modules.offer.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新增 Offer（契约 CreateOfferRequest）：companyId 与 jobPositionId 至少填一个，
 * 仅填岗位时服务层自动带出所属公司。
 */
public record CreateOfferRequest(
        Long companyId,
        Long jobPositionId,
        @Min(0) @Max(10000000) Integer monthlySalary,
        @Min(0) @Max(10000000) Integer baseSalary,
        @DecimalMin("0") @DecimalMax("100") BigDecimal performanceRatio,
        @Size(max = 64) String socialInsuranceBase,
        @Size(max = 32) String socialInsuranceRatio,
        @Size(max = 64) String housingFundBase,
        @Size(max = 32) String housingFundRatio,
        Boolean twoDayWeekend,
        @Size(max = 64) String workHours,
        List<@Size(max = 64) String> benefits,
        List<@Size(max = 64) String> allowances,
        @Size(max = 64) String annualBonus,
        @Size(max = 64) String quarterlyBonus,
        @Size(max = 500) String remark,
        @Pattern(regexp = "KEEP|DROP", message = "group 取值非法") String group) {
}
