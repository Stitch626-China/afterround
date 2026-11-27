package com.jobpilot.modules.eval.dto;

/** 成本对比：总 tokens、平均 tokens 与差值。 */
public record EvalCompareCost(
        Long leftTokens,
        Long rightTokens,
        Long delta,
        Double leftAvgTokens,
        Double rightAvgTokens) {
}
