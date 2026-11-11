package com.jobpilot.modules.llm.dto;

/** 连通性测试结果（契约 ConnectivityTest）。 */
public record ConnectivityTestResponse(
        boolean success,
        long latencyMs,
        String message,
        String upstreamModel) {
}
