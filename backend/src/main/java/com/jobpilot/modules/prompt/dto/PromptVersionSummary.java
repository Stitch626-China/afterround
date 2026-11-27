package com.jobpilot.modules.prompt.dto;

import java.time.OffsetDateTime;

import com.jobpilot.modules.prompt.catalog.PromptVersionSource;

/** 提示词版本摘要（不含全文）。 */
public record PromptVersionSummary(
        Integer versionNo,
        PromptVersionSource source,
        String status,
        String contentHash,
        String note,
        OffsetDateTime createdAt,
        OffsetDateTime publishedAt) {
}
