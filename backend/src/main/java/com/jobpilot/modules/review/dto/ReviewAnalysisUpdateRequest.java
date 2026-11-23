package com.jobpilot.modules.review.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;

/**
 * 确认 / 修正结构化分析（契约 ReviewAnalysisUpdateRequest）：完整 payload 覆盖可编辑字段。
 * confirmed=true 时据 actions 创建或更新改进行动。
 */
public record ReviewAnalysisUpdateRequest(
        @NotNull List<ReviewQuestionResponse> questions,
        @NotNull List<ReviewTagResponse> topics,
        @NotNull List<ReviewTagResponse> lossTags,
        @NotNull ReviewSummaryResponse summary,
        @NotNull List<ImprovementActionDraftResponse> actions,
        @NotNull Boolean confirmed) {
}
