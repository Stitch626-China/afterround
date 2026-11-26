package com.jobpilot.modules.job.dto;

import java.time.OffsetDateTime;

/** 岗位列表项（契约 JobPositionListItem）：解析完成前结构化字段可能为 null。 */
public record JobPositionListItemResponse(
        Long id,
        String sourceType,
        String title,
        Long companyId,
        String companyName,
        String city,
        Integer salaryMin,
        Integer salaryMax,
        String experience,
        String education,
        String status,
        String remark,
        String parseStatus,
        Integer retryCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
