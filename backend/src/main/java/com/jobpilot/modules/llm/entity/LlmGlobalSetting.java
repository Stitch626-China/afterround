package com.jobpilot.modules.llm.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 全局默认 Provider + Model，单行表（id 固定为 1）。 */
@Data
@TableName("llm_global_setting")
public class LlmGlobalSetting {

    @TableId(type = IdType.INPUT)
    private Integer id;
    private Long userId;
    private String defaultProviderKey;
    private String defaultModel;
    private OffsetDateTime updatedAt;
}
