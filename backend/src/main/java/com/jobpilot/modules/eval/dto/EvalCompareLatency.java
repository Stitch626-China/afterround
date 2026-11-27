package com.jobpilot.modules.eval.dto;

/** 耗时对比：平均、P95 与差值。 */
public record EvalCompareLatency(
        Double leftAvgMs,
        Double rightAvgMs,
        Double avgDelta,
        Double leftP95Ms,
        Double rightP95Ms,
        Double p95Delta) {
}
