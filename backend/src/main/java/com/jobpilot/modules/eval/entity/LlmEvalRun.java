package com.jobpilot.modules.eval.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jobpilot.common.mybatis.JsonbStringTypeHandler;
import lombok.Data;

/** 一次评测运行；JSONB 字段以 String 承载，业务层负责序列化。 */
@Data
@TableName(value = "llm_eval_runs", autoResultMap = true)
public class LlmEvalRun {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String status;
    private Boolean partial;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String scenes;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String modelSnapshot;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String promptSnapshot;
    private String corpusHash;
    private String engineVersion;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String params;
    private Integer totalCases;
    private Integer completedCases;
    private Integer errorCases;
    private String error;
    private Integer retryCount;
    private Boolean cancelRequested;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;
    private OffsetDateTime createdAt;
}
