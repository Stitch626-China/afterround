package com.jobpilot.modules.chat.dto;

import jakarta.validation.constraints.Size;

/** 创建会话；省略 providerKey/model 时采用全局默认。 */
public record CreateChatSessionRequest(
        @Size(max = 128) String title,
        String providerKey,
        String model) {
}
