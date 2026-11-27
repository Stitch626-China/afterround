package com.jobpilot.modules.eval.dto;

import java.util.List;

/** 逐用例变化：一个 case 可同时有多项（如分数漂移 + 标签变化）。 */
public record EvalCompareCase(
        String caseId,
        String leftStatus,
        String rightStatus,
        List<String> changes,
        Double scoreDrift,
        Double tagJaccard,
        String details) {
}
