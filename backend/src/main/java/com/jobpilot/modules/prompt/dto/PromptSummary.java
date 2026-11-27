package com.jobpilot.modules.prompt.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.jobpilot.modules.prompt.catalog.PromptModelScope;
import com.jobpilot.modules.prompt.catalog.PromptOutputMode;

/** 提示词列表摘要。 */
public record PromptSummary(
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
        OffsetDateTime updatedAt) {
}
