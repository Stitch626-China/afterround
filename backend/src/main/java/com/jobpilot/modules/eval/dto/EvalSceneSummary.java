package com.jobpilot.modules.eval.dto;

import java.util.List;

/** 评测场景摘要：语料数量、生效提示词、生效模型、当前基线与重建原因。 */
public record EvalSceneSummary(
        String scene,
        String name,
        int caseCount,
        String corpusHash,
        String promptKey,
        String promptVersion,
        String modelScope,
        String providerKey,
        String model,
        EvalBaselineSummary currentBaseline,
        EvalRunSummary lastRun,
        List<String> rebuildReasons) {
}
