package com.jobpilot.modules.offer.dto;

import java.util.List;

/** 保留组 Offer 的同维度对比矩阵（FR6.3）；保留组为空时两项均为空数组。 */
public record OfferComparison(List<OfferComparisonColumn> columns, List<OfferComparisonRow> rows) {
}
