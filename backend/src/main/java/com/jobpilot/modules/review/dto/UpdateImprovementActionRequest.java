package com.jobpilot.modules.review.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 更新改进行动（契约 UpdateImprovementActionRequest）：未传不修改，显式 null 清空可选字段。
 * status 取值 TODO / IN_PROGRESS / VERIFIED / DROPPED。
 */
public record UpdateImprovementActionRequest(
        JsonNode action,
        JsonNode category,
        JsonNode status,
        JsonNode dueAt,
        JsonNode verifiedEventId,
        JsonNode evidence) {
}
