package com.jobpilot.modules.resume.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** 更新薪资（元/月）；传 null 表示清空用户值，回落为 LLM 提取值。 */
public record UpdateSalaryRequest(
        @Min(0) @Max(1000000) Integer currentMonthlySalary,
        @Min(0) @Max(1000000) Integer expectedMonthlySalary) {
}
