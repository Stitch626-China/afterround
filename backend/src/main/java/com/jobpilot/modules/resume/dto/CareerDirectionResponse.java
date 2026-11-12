package com.jobpilot.modules.resume.dto;

import java.util.List;

/** 岗位方向推荐（契约 CareerDirection）。 */
public record CareerDirectionResponse(
        String direction,
        String matchReason,
        List<String> skillGaps,
        List<String> interviewFocus) {
}
