package com.jobpilot.modules.prompt.dto;

import java.time.OffsetDateTime;

import com.jobpilot.modules.prompt.catalog.PromptVersionSource;

/** 提示词版本详情（含 system/user 全文）。 */
public record PromptVersionDetail(
        Integer versionNo,
        PromptVersionSource source,
        String status,
        String contentHash,
        String note,
        OffsetDateTime createdAt,
        OffsetDateTime publishedAt,
        String systemContent,
        String userContent) {
}
