package com.jobpilot.modules.eval.dto;

/** 回归变化计数；一个 case 可同时命中多项。 */
public record EvalCompareRegression(int fixedCases, int newFailedCases, int scoreDriftCases, int tagChangedCases) {
}
