package com.jobpilot.modules.llm.dto;

import jakarta.validation.constraints.Size;

/** 更新模型（改名请删除后重建）。 */
public record UpdateModelRequest(
        @Size(max = 64) String label,
        Boolean enabled,
        Boolean isDefault,
        Integer sortOrder) {
}
