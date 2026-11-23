package com.jobpilot.modules.application.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 投递档案（F8）：同一岗位允许多次投递，按简历版本或投递时间区分。 */
@Data
@TableName("applications")
public class JobApplication {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long jobPositionId;
    private Long resumeId;
    private String channel;
    private OffsetDateTime appliedAt;
    private String status;
    private String outcome;
    private String notes;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
