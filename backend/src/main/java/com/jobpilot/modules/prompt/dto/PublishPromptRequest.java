package com.jobpilot.modules.prompt.dto;

import jakarta.validation.constraints.Size;

/** 发布草稿；note 可选，用于补充发布说明。 */
public record PublishPromptRequest(@Size(max = 200) String note) {
}
