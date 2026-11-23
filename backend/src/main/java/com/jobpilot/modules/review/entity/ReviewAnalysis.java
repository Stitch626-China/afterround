package com.jobpilot.modules.review.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jobpilot.common.mybatis.JsonbStringTypeHandler;
import lombok.Data;

/** 复盘结构化分析（FR4.3/FR4.7）：与复盘一一对应；重跑覆盖，保留 Prompt / rubric / 词表版本与实际模型。 */
@Data
@TableName(value = "review_analyses", autoResultMap = true)
public class ReviewAnalysis {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long reviewSessionId;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String analysis;

    private String promptVersion;
    private String rubricVersion;
    private String taxonomyVersion;
    private String llmProvider;
    private String llmModel;
    private Boolean degraded;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
