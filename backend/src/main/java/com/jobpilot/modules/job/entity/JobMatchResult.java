package com.jobpilot.modules.job.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jobpilot.common.mybatis.JsonbStringTypeHandler;
import lombok.Data;

/** 简历-JD 匹配结果；同一 (resume_id, job_position_id) 唯一，重复发起即重跑。 */
@Data
@TableName(value = "job_match_results", autoResultMap = true)
public class JobMatchResult {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long resumeId;
    private Long jobPositionId;
    private String status;
    private String error;
    private Integer retryCount;
    private Integer overallScore;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String detail;

    private String llmProvider;
    private String llmModel;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
