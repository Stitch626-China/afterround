package com.jobpilot.modules.eval.dto;

/** 质量门禁：L1 按用例、L2 按断言条目；旧导入基线无明细时为 null。 */
public record EvalCompareQuality(
        Double l1PassRate,
        Double l2PassRate,
        Double l1Delta,
        Double l2Delta,
        Integer l1Failed,
        Integer l2Failed) {
}
