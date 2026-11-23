package com.jobpilot.modules.review.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 改进动作（FR4.4/FR5.6）：来自复盘或手动新增，可在后续面试验证是否生效。 */
@Data
@TableName("improvement_actions")
public class ImprovementAction {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long applicationId;
    private Long sourceReviewId;
    private Long sourceEventId;
    private String action;
    private String category;
    private String status;
    private OffsetDateTime dueAt;
    private Long verifiedEventId;
    private String evidence;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
