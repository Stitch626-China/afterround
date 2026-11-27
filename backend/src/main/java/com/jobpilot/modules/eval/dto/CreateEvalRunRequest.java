package com.jobpilot.modules.eval.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** 创建评测运行：P2 只支持选择场景；P3 追加 sourceRunId + retryErrorsOnly。 */
public record CreateEvalRunRequest(
        @NotEmpty List<@NotBlank String> scenes,
        @Min(1) @Max(2) Integer repeat) {
}
