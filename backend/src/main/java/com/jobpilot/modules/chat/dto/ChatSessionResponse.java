package com.jobpilot.modules.chat.dto;

import java.time.OffsetDateTime;

import com.jobpilot.modules.chat.entity.ChatSession;

/** 会话展示对象（契约 ChatSession）。 */
public record ChatSessionResponse(
        Long id,
        String title,
        String currentProviderKey,
        String currentModel,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static ChatSessionResponse from(ChatSession entity) {
        return new ChatSessionResponse(entity.getId(), entity.getTitle(), entity.getCurrentProviderKey(),
                entity.getCurrentModel(), entity.getStatus(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
