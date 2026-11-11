package com.jobpilot.modules.llm.dto;

import jakarta.validation.constraints.NotBlank;

/** 设置全局默认 Provider + Model，需校验模型属于该 Provider。 */
public record UpdateGlobalSettingRequest(
        @NotBlank String defaultProviderKey,
        @NotBlank String defaultModel) {
}
