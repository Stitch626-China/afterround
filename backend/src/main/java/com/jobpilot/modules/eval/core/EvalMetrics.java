package com.jobpilot.modules.eval.core;

/** 运行级指标；字段为 null 表示无可统计数据（如旧导入基线缺少明细）。 */
public record EvalMetrics(Integer l1Total, Integer l1Failed, Double l1PassRate, Integer l2Total,
                          Integer l2Failed, Double l2PassRate, Long totalTokens, Double avgTokens,
                          Double avgElapsedMs, Double p95ElapsedMs) {
}
