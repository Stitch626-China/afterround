package com.jobpilot.modules.eval.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.jobpilot.modules.eval.core.EvalMetrics;

/** 运行详情：摘要 + 逐用例 + 启动快照 + 聚合指标。 */
public record EvalRunDetail(
        Long id,
        String status,
        List<String> scenes,
        int totalCases,
        int completedCases,
        int errorCases,
        String error,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        List<EvalCaseResult> cases,
        List<EvalSceneModel> models,
        List<EvalScenePrompt> prompts,
        EvalMetrics metrics) {
}
