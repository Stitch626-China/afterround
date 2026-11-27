package com.jobpilot.modules.prompt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 保存提示词草稿；不影响线上。 */
public record SavePromptDraftRequest(
        @NotBlank @Size(max = 20000) String systemContent,
        @NotBlank @Size(max = 20000) String userContent,
        @Size(max = 200) String note) {
}
