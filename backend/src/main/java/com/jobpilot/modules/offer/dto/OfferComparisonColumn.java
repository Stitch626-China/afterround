package com.jobpilot.modules.offer.dto;

/** 对比列：保留组 Offer 的顺序即矩阵列顺序（FR6.3）。 */
public record OfferComparisonColumn(Long offerId, String companyName, String jobPositionTitle) {
}
