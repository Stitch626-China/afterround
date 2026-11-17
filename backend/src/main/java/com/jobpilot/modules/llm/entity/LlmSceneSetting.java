package com.jobpilot.modules.llm.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 场景级 Provider + Model 绑定；未配置时回落全局默认（FRX.11）。 */
@Data
@TableName("llm_scene_settings")
public class LlmSceneSetting {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String scene;
    private String providerKey;
    private String model;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
