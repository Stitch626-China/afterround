package com.jobpilot.modules.eval.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.List;

/**
 * 创建评测运行：普通运行给 scenes；部分重跑给 sourceRunId + retryErrorsOnly。
 * 二选一与互斥校验在服务端完成，便于给出可操作提示。
 */
public record CreateEvalRunRequest(
        List<String> scenes,
        @Min(1) @Max(2) Integer repeat,
        Long sourceRunId,
        Boolean retryErrorsOnly) {
}
