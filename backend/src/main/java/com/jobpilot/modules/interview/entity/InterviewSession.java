package com.jobpilot.modules.interview.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jobpilot.common.mybatis.JsonbStringTypeHandler;
import lombok.Data;

/** 模拟面试会话；出题状态与评估状态各自一套。 */
@Data
@TableName(value = "interview_sessions", autoResultMap = true)
public class InterviewSession {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String direction;
    private String directionLabel;
    private Long resumeId;
    private Long jobPositionId;
    private String status;
    private Integer questionCount;
    private Integer followUpCount;
    private Integer answeredCount;
    private String currentProviderKey;
    private String currentModel;
    private String error;
    private Integer retryCount;
    private String evaluateStatus;
    private String evaluateError;
    private Integer evaluateRetryCount;
    private Integer evaluateOverallScore;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String evaluateCategories;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String evaluateStrengths;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String evaluateImprovements;

    private String evaluateSummary;
    private String evaluateProvider;
    private String evaluateModel;
    private OffsetDateTime evaluatedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
