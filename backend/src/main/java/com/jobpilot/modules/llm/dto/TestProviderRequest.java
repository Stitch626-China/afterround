package com.jobpilot.modules.llm.dto;

/** 连通性测试参数；省略 modelName 时使用该 Provider 的默认模型。 */
public record TestProviderRequest(String modelName) {
}
