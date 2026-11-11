package com.jobpilot.modules.llm.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 在 Provider 下新增模型（契约 CreateModelRequest）。 */
public record CreateModelRequest(
        @NotBlank @Size(max = 128) String modelName,
        @NotBlank @Size(max = 64) String label,
        Boolean enabled,
        Boolean isDefault,
        Integer sortOrder) {
}
