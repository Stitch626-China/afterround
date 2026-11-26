package com.jobpilot.modules.llm.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** token 成本统计（NFR6）：聚合 llm_call_logs，不落新表；无数据时比例返回 null。 */
public record LlmUsageResponse(int days, OffsetDateTime generatedAt, Summary summary,
                               List<Scene> scenes, List<Model> models, List<Day> daily) {

    public record Summary(int calls, int successCalls, Double successRate,
                          long promptTokens, long completionTokens, long totalTokens,
                          long avgDurationMs) {
    }

    public record Scene(String scene, String label, int calls, int successCalls, Double successRate,
                        long totalTokens, long avgDurationMs) {
    }

    public record Model(String providerKey, String model, int calls, long totalTokens) {
    }

    public record Day(String date, int calls, long totalTokens) {
    }
}
