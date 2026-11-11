package com.jobpilot.modules.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 发送消息（SSE 流式返回）。 */
public record SendMessageRequest(
        @NotBlank @Size(max = 20000) String content) {
}
