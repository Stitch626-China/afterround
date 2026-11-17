package com.jobpilot.modules.llm.dto;

import java.time.OffsetDateTime;

/**
 * 场景绑定视图（契约 SceneBinding）：显式值与回落后的生效值同时返回。
 */
public record SceneBindingResponse(
        LlmScene scene,
        String label,
        String providerKey,
        String model,
        boolean inherited,
        String effectiveProviderKey,
        String effectiveModel,
        OffsetDateTime updatedAt) {
}
