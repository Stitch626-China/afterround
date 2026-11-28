package com.jobpilot.modules.eval.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jobpilot.common.mybatis.JsonbStringTypeHandler;
import lombok.Data;

/** 场景级基线：被「设为基线」的运行；历史保留，is_current 只做标记。 */
@Data
@TableName(value = "llm_eval_baselines", autoResultMap = true)
public class LlmEvalBaseline {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String scene;
    private Long runId;
    /** 可清空：重复设为基线时传空要真正清掉旧标签。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String label;
    private String providerKey;
    private String model;
    private String promptVersion;
    private String promptHash;
    private String corpusHash;
    private String engineVersion;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String params;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String metrics;
    @TableField("is_current")
    private Boolean current;
    private OffsetDateTime createdAt;
}
