package com.jobpilot.modules.chat.dto;

import java.time.OffsetDateTime;

import com.jobpilot.modules.chat.entity.ChatMessage;

/** 消息展示对象（契约 ChatMessage）。 */
public record ChatMessageResponse(
        Long id,
        String role,
        String content,
        String llmProvider,
        String llmModel,
        String traceId,
        OffsetDateTime createdAt) {

    public static ChatMessageResponse from(ChatMessage entity) {
        return new ChatMessageResponse(entity.getId(), entity.getRole(), entity.getContent(),
                entity.getLlmProvider(), entity.getLlmModel(), entity.getTraceId(), entity.getCreatedAt());
    }
}
