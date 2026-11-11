package com.jobpilot.modules.chat.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 会话消息；ASSISTANT 消息冗余实际使用的 provider + model，用于追溯。 */
@Data
@TableName("chat_messages")
public class ChatMessage {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long sessionId;
    private String role;
    private String content;
    private String llmProvider;
    private String llmModel;
    private String traceId;
    private OffsetDateTime createdAt;
}
