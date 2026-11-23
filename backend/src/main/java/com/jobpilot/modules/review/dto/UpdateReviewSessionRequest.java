package com.jobpilot.modules.review.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 修正转写 / 笔记 / 面试上下文（契约 UpdateReviewSessionRequest）：未传不修改，显式 null 清空可选字段。
 * 修正后需调用 /analyze 重新分析才会更新分析结果。
 */
public record UpdateReviewSessionRequest(
        JsonNode transcript,
        JsonNode notes,
        JsonNode interviewerRole,
        JsonNode roundType,
        JsonNode format,
        JsonNode interviewTime) {
}
