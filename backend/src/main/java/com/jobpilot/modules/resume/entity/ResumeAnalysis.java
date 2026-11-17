package com.jobpilot.modules.resume.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jobpilot.common.mybatis.JsonbStringTypeHandler;
import lombok.Data;

/** 简历分析结果，与 resumes 一对一；JSONB 字段以 JSON 文本读写。 */
@Data
@TableName(value = "resume_analyses", autoResultMap = true)
public class ResumeAnalysis {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long resumeId;
    private Integer overallScore;
    private Integer scoreTechnicalDepth;
    private Integer scoreProjectQuality;
    private Integer scoreEngineeringAbility;
    private Integer scoreExpressionStructure;
    private Integer scoreJobMatchPotential;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String basicInfo;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String strengths;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String weaknesses;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String suggestions;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String careerDirections;

    private String llmProvider;
    private String llmModel;
    private String promptVersion;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
