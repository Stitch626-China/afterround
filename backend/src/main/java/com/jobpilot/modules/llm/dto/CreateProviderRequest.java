package com.jobpilot.modules.llm.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 新增 Provider（契约 CreateProviderRequest）。 */
public record CreateProviderRequest(
        @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,63}$", message = "providerKey 只能是小写字母/数字/短横线")
        String providerKey,
        @NotBlank @Size(max = 64) String label,
        @NotBlank @Size(max = 512) @Pattern(regexp = "^https?://.+", message = "baseUrl 必须是 http(s) 地址")
        String baseUrl,
        @NotBlank String apiKey,
        Boolean enabled,
        Integer sortOrder) {
}
