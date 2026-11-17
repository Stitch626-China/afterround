package com.jobpilot.modules.job.ai;

import java.util.List;

/** JD 解析的 AI 中间 DTO：只承载模型输出，业务落库前由本地校验与清洗。 */
public record AiJobPositionParse(
        String title,
        String companyName,
        String city,
        Integer salaryMin,
        Integer salaryMax,
        String experience,
        String education,
        List<String> mustHaveSkills,
        List<String> niceToHaveSkills,
        String responsibility) {
}
