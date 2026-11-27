package com.jobpilot.modules.eval.dto;

import java.util.List;

/** 两次运行 4 维对比；不可比时 comparable=false，指标为 null。 */
public record EvalRunCompare(
        boolean comparable,
        List<String> reasons,
        List<String> warnings,
        List<String> applicableDimensions,
        String scene,
        EvalRunCompareSide left,
        EvalRunCompareSide right,
        EvalCompareQuality quality,
        EvalCompareRegression regression,
        EvalCompareCost cost,
        EvalCompareLatency latency,
        List<EvalCompareCase> cases) {
}
