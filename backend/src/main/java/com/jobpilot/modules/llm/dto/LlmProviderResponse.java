package com.jobpilot.modules.llm.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** Provider 展示对象（契约 LlmProvider），API Key 只返回掩码。 */
public record LlmProviderResponse(
        String providerKey,
        String label,
        String baseUrl,
        String apiKeyMasked,
        Boolean enabled,
        Integer sortOrder,
        List<LlmModelResponse> models,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
