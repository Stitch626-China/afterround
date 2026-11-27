package com.jobpilot.modules.eval.dto;

/** 「设为基线」的逐场景结果；部分成功可区分。 */
public record EvalBaselineResult(String scene, boolean created, Long baselineId, String reason) {
}
