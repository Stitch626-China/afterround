package com.jobpilot.modules.offer.dto;

import java.util.List;

/** 对比行：固定维度、固定顺序，缺失值统一为「未填写」。 */
public record OfferComparisonRow(String key, String label, List<String> values) {
}
