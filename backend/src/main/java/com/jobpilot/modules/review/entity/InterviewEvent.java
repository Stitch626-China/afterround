package com.jobpilot.modules.review.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 真实面试轮次（FR5.1）：一次投递下同一轮次唯一，是复盘与跨场归因的锚点。 */
@Data
@TableName("interview_events")
public class InterviewEvent {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long applicationId;
    private Integer roundNo;
    private String roundType;
    private String interviewerRole;
    private String format;
    private OffsetDateTime interviewTime;
    private String result;
    private String notes;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
