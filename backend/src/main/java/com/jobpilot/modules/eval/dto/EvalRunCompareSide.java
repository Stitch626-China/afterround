package com.jobpilot.modules.eval.dto;

import java.util.Map;

import com.jobpilot.modules.eval.core.EvalMetrics;

/** 对比一侧的运行快照；基线只是被标记的运行。 */
public record EvalRunCompareSide(
        Long runId,
        String scene,
        String providerKey,
        String model,
        String promptVersion,
        String promptHash,
        String corpusHash,
        String engineVersion,
        Map<String, Object> params,
        EvalMetrics metrics,
        Long baselineId,
        String baselineLabel) {
}
