package com.jobpilot.modules.review.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 更新真实面试轮次（契约 UpdateInterviewEventRequest）：未传不修改，显式 null 清空可选字段。
 * roundType / result / interviewTime 为必填语义，显式 null 返回 400。
 */
public record UpdateInterviewEventRequest(
        JsonNode roundType,
        JsonNode interviewerRole,
        JsonNode format,
        JsonNode interviewTime,
        JsonNode result,
        JsonNode notes) {
}
