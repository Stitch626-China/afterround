package com.jobpilot.modules.interview.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** 面试会话详情（轮询端点）：摘要 + 失败信息 + 题目列表（FR3.4 / FR3.6）。 */
public record InterviewSessionDetail(
        Long id,
        String direction,
        String directionLabel,
        Long resumeId,
        String resumeFileName,
        Long jobPositionId,
        String jobPositionTitle,
        String companyName,
        String status,
        String evaluateStatus,
        Integer questionCount,
        Integer followUpCount,
        Integer answeredCount,
        String currentProviderKey,
        String currentModel,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String error,
        Integer retryCount,
        String evaluateError,
        Integer evaluateRetryCount,
        List<InterviewQuestion> questions) {
}
