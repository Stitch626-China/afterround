package com.jobpilot.modules.llm.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** Provider 下挂载的模型；请求时以 modelName 原样传给上游。 */
@Data
@TableName("llm_models")
public class LlmModel {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long providerId;
    private String modelName;
    private String label;
    private Boolean enabled;
    private Boolean isDefault;
    private Integer sortOrder;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
