package com.jobpilot.modules.eval.dto;

/** 单条断言结果（L1 硬断言 / L2 质量断言 / L3 稳定性）。 */
public record EvalAssertionResult(String layer, String name, boolean passed, String detail) {
}
