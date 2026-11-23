package com.jobpilot.modules.review.dto;

/** 受控词表标签（契约 ReviewTag）：key + 展示名 + 置信度 + 证据。 */
public record ReviewTagResponse(
        String tag,
        String label,
        Double confidence,
        String evidence) {
}
