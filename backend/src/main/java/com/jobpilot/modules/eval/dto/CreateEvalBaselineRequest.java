package com.jobpilot.modules.eval.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 把已完成的运行设为基线；只贴标签，不重跑。 */
public record CreateEvalBaselineRequest(
        @NotNull Long runId,
        @Size(max = 100) String label) {
}
