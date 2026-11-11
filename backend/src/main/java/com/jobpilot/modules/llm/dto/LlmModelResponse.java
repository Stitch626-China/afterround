package com.jobpilot.modules.llm.dto;

import java.time.OffsetDateTime;

import com.jobpilot.modules.llm.entity.LlmModel;

/** 模型展示对象（契约 LlmModel）。 */
public record LlmModelResponse(
        String modelName,
        String label,
        Boolean enabled,
        Boolean isDefault,
        Integer sortOrder,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static LlmModelResponse from(LlmModel entity) {
        return new LlmModelResponse(entity.getModelName(), entity.getLabel(), entity.getEnabled(),
                entity.getIsDefault(), entity.getSortOrder(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
