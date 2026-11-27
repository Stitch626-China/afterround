package com.jobpilot.modules.eval.dto;

import java.util.List;
import java.util.Map;

/** 逐用例结果；FAIL 为质量失败，ERROR 为执行错误。 */
public record EvalCaseResult(
        String scene,
        String caseId,
        String status,
        Boolean l1Passed,
        Boolean l2Passed,
        Boolean l3Passed,
        List<EvalAssertionResult> results,
        Map<String, Double> scores,
        Integer tokens,
        Long elapsedMs,
        String error) {
}
