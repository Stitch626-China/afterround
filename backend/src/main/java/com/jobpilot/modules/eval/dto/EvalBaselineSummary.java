package com.jobpilot.modules.eval.dto;

import java.time.OffsetDateTime;
import java.util.Map;

import com.jobpilot.modules.eval.core.EvalMetrics;

/** 场景级基线（被「设为基线」的那次运行）；P2 先定义供场景摘要引用，P3 复用。 */
public record EvalBaselineSummary(
        Long id,
        String scene,
        Long runId,
        String label,
        String providerKey,
        String model,
        String promptVersion,
        String promptHash,
        String corpusHash,
        String engineVersion,
        Map<String, Object> params,
        EvalMetrics metrics,
        boolean current,
        OffsetDateTime createdAt) {
}
