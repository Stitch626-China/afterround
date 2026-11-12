package com.jobpilot.modules.resume.dto;

/** 基本信息（契约 ResumeBasicInfo）；薪资为用户值优先、LLM 提取值兜底。 */
public record ResumeBasicInfoResponse(
        String name,
        Double yearsOfExperience,
        String techDirection,
        Integer currentMonthlySalary,
        Integer expectedMonthlySalary) {
}
