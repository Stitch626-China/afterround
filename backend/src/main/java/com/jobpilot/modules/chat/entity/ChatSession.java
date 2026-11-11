package com.jobpilot.modules.chat.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 对话会话（M1 能力验证载体）：IDLE 可切换可发送 / PROCESSING 禁切 / LOCKED 终态。 */
@Data
@TableName("chat_sessions")
public class ChatSession {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String title;
    private String currentProviderKey;
    private String currentModel;
    private String status;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
