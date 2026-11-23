package com.jobpilot.modules.review.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 复盘会话（FR4）：一条面试事件只允许一条复盘；ASR 与分析阶段分别记录状态与重试。 */
@Data
@TableName("review_sessions")
public class ReviewSession {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long eventId;
    private String inputType;
    private String audioStorageKey;
    private String audioFileName;
    private Long audioSize;
    private Integer audioDurationSeconds;
    private String transcript;
    private String transcriptSegments;
    private String notes;
    private String asrStatus;
    private String asrError;
    private Integer asrRetryCount;
    private String analyzeStatus;
    private String analyzeError;
    private Integer analyzeRetryCount;
    private String status;
    private String idempotencyKey;
    private Boolean confirmed;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
