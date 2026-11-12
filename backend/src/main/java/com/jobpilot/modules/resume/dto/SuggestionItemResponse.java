package com.jobpilot.modules.resume.dto;

/** 原句 vs 优化句（契约 SuggestionItem）。 */
public record SuggestionItemResponse(String original, String improved, String reason) {
}
