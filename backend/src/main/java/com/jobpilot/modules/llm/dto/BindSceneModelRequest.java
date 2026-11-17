package com.jobpilot.modules.llm.dto;

import jakarta.validation.constraints.NotBlank;

public record BindSceneModelRequest(
        @NotBlank String providerKey,
        @NotBlank String model) {
}
