package com.jobpilot.modules.application.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 更新投递（契约 UpdateJobApplicationRequest）：未传不修改，显式 null 清空。
 * 与 UpdateOfferRequest 一致，用 {@link JsonNode} 区分「未传」与「显式 null」。
 */
public record UpdateJobApplicationRequest(
        JsonNode resumeId,
        JsonNode channel,
        JsonNode appliedAt,
        JsonNode status,
        JsonNode outcome,
        JsonNode notes) {
}
