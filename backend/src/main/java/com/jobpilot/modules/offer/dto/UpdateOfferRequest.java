package com.jobpilot.modules.offer.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 更新 Offer（契约 UpdateOfferRequest）：全部字段可选。
 *
 * <p>用 {@link JsonNode} 区分「未传」与「显式 null」：未传（field == null）不修改，
 * 显式 null（NullNode）表示清空。与 UpdateJobPositionRequest 的 companyId 处理一致。
 */
public record UpdateOfferRequest(
        JsonNode companyId,
        JsonNode jobPositionId,
        JsonNode applicationId,
        JsonNode monthlySalary,
        JsonNode baseSalary,
        JsonNode performanceRatio,
        JsonNode socialInsuranceBase,
        JsonNode socialInsuranceRatio,
        JsonNode housingFundBase,
        JsonNode housingFundRatio,
        JsonNode twoDayWeekend,
        JsonNode workHours,
        JsonNode benefits,
        JsonNode allowances,
        JsonNode annualBonus,
        JsonNode quarterlyBonus,
        JsonNode remark,
        JsonNode group) {
}
