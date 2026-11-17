package com.jobpilot.modules.job.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** 岗位详情（契约 JobPositionDetail）：列表字段 + 原文与解析结果，兼作解析轮询端点。 */
public record JobPositionDetailResponse(
        Long id,
        String title,
        Long companyId,
        String companyName,
        String city,
        Integer salaryMin,
        Integer salaryMax,
        String experience,
        String education,
        String status,
        String parseStatus,
        Integer retryCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String sourceType,
        String fileName,
        String rawText,
        List<String> mustHaveSkills,
        List<String> niceToHaveSkills,
        String responsibility,
        String parseError,
        String llmProvider,
        String llmModel) {
}
