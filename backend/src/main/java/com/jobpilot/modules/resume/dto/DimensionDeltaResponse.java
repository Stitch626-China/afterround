package com.jobpilot.modules.resume.dto;

/** 五维变化（契约 DimensionDelta）：delta = target - base。 */
public record DimensionDeltaResponse(String key, String label, Integer baseScore, Integer targetScore, Integer delta) {
}
