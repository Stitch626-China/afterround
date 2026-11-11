package com.jobpilot.modules.chat.dto;

import jakarta.validation.constraints.NotBlank;

/** 会话内两级切换：Provider + Model 必须成对指定。 */
public record SwitchModelRequest(
        @NotBlank String providerKey,
        @NotBlank String model) {
}
