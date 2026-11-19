package com.jobpilot.modules.interview.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/**
 * 创建模拟面试（契约 CreateInterviewSessionRequest）。
 *
 * <p>direction 与 jobPositionId 至少填一个，由服务层校验；仅关联 JD 时按 JD 内容匹配方向。
 */
public record CreateInterviewSessionRequest(
        @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,63}$", message = "格式不合法") String direction,
        Long resumeId,
        Long jobPositionId,
        @Min(5) @Max(15) Integer questionCount,
        @Min(0) @Max(3) Integer followUpCount,
        String providerKey,
        String model) {
}
