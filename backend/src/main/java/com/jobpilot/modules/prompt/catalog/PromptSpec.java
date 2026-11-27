package com.jobpilot.modules.prompt.catalog;

import java.util.List;

import com.jobpilot.modules.llm.dto.LlmScene;

/**
 * 一组提示词的静态元数据。
 *
 * <p>模板文件、占位符、必填 JSON 字段、模型来源与评测场景都在代码里维护，
 * 避免元数据与调用方漂移；DB 只存可编辑的内容版本。
 */
public record PromptSpec(
        String key,
        String name,
        String category,
        String description,
        String systemFile,
        String userFile,
        List<String> placeholders,
        List<String> requiredFields,
        PromptOutputMode outputMode,
        PromptModelScope modelScope,
        LlmScene scene,
        String evalScene,
        List<String> affectedFunctions,
        String builtinVersion) {

    public PromptSpec {
        placeholders = List.copyOf(placeholders);
        requiredFields = List.copyOf(requiredFields);
        affectedFunctions = List.copyOf(affectedFunctions);
    }
}
