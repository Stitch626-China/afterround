package com.jobpilot.modules.llm.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** LLM 调用埋点（NFR2 / NFR6）。 */
@Data
@TableName("llm_call_logs")
public class LlmCallLog {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String traceId;
    private String scene;
    private String providerKey;
    private String model;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Long durationMs;
    private Boolean success;
    private String error;
    private OffsetDateTime createdAt;
}
