package com.jobpilot.modules.prompt.service;

import com.jobpilot.modules.prompt.catalog.PromptVersionSource;

/** 渲染前的提示词模板；versionLabel 用于分析结果追溯。 */
public record PromptTemplate(
        String key,
        String system,
        String user,
        String versionLabel,
        String contentHash,
        PromptVersionSource source) {
}
