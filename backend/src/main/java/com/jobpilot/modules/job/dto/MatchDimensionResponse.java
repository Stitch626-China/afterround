package com.jobpilot.modules.job.dto;

/** 匹配分项（契约 MatchDimension）：权重由本地固定为 50/30/20，不信任模型输出。 */
public record MatchDimensionResponse(String key, String label, Integer score, Integer weight) {
}
