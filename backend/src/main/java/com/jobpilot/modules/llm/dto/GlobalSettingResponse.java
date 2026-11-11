package com.jobpilot.modules.llm.dto;

/** 全局默认设置（契约 GlobalSetting）。 */
public record GlobalSettingResponse(String defaultProviderKey, String defaultModel) {
}
