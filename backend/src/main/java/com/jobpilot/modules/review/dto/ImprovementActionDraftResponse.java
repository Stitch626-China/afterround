package com.jobpilot.modules.review.dto;

/** 改进行动草稿（契约 ImprovementActionDraft）：确认复盘时据此创建或更新改进行动。 */
public record ImprovementActionDraftResponse(
        String action,
        String category,
        String reason) {
}
