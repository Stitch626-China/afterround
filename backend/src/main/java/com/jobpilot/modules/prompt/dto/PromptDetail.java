package com.jobpilot.modules.prompt.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.jobpilot.modules.prompt.catalog.PromptModelScope;
import com.jobpilot.modules.prompt.catalog.PromptOutputMode;

/**
 * 提示词详情；为匹配契约的 allOf 结构，把摘要字段平铺在顶层。
 */
public record PromptDetail(
        String key,
        String name,
        String category,
        String description,
        PromptOutputMode outputMode,
        PromptModelScope modelScope,
        String scene,
        String evalScene,
        boolean hasEvalCorpus,
        List<String> affectedFunctions,
        List<String> placeholders,
        List<String> requiredFields,
        PromptVersionSummary activeVersion,
        String builtinVersion,
        boolean hasDraft,
        OffsetDateTime updatedAt,
        PromptVersionDetail active,
        PromptVersionDetail builtIn,
        PromptVersionDetail draft,
        PromptValidation validation) {
}
