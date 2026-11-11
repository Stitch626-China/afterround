package com.jobpilot.modules.llm.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 更新 Provider；apiKey 省略或空串表示保持原密钥。 */
public record UpdateProviderRequest(
        @Size(max = 64) String label,
        @Size(max = 512) @Pattern(regexp = "^https?://.+", message = "baseUrl 必须是 http(s) 地址")
        String baseUrl,
        String apiKey,
        Boolean enabled,
        Integer sortOrder) {
}
