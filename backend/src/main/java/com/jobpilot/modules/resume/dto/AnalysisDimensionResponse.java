package com.jobpilot.modules.resume.dto;

/** 单维评分（契约 AnalysisDimension），weight 为百分比。 */
public record AnalysisDimensionResponse(String key, String label, Integer score, Integer weight) {
}
