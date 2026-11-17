package com.jobpilot.modules.company.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record CompanyResponse(
        Long id,
        String name,
        String intro,
        List<String> tags,
        Integer jobPositionCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
