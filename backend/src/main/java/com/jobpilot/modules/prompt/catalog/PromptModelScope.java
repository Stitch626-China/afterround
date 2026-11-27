package com.jobpilot.modules.prompt.catalog;

/** 提示词使用的模型来源：场景绑定 / 全局默认 / 会话创建时模型。 */
public enum PromptModelScope {
    SCENE,
    GLOBAL,
    SESSION
}
