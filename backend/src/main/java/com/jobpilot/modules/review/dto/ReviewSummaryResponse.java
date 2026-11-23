package com.jobpilot.modules.review.dto;

import java.util.List;

/** 复盘总结（契约 ReviewSummary）：整体评价、优势、待改进与下一步准备。 */
public record ReviewSummaryResponse(
        String overall,
        List<String> strengths,
        List<String> improvements,
        List<String> nextPreparation) {
}
