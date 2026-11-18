package com.jobpilot.modules.job.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jobpilot.common.mybatis.JsonbStringTypeHandler;
import lombok.Data;

/** JD 原文 + LLM 结构化解析结果。 */
@Data
@TableName(value = "job_positions", autoResultMap = true)
public class JobPosition {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long companyId;
    private String title;
    private String city;
    private Integer salaryMin;
    private Integer salaryMax;
    private String experience;
    private String education;
    private String sourceType;
    private String fileName;
    private String storageKey;
    private String rawText;

    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String mustHaveSkills;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String niceToHaveSkills;

    private String responsibility;
    private String status;
    /** null 是合法值（清空备注），因此更新时始终写入该列。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String remark;
    private String parseStatus;
    private String parseError;
    private Integer retryCount;
    private String llmProvider;
    private String llmModel;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
