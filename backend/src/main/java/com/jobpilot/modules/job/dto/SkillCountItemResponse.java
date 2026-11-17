package com.jobpilot.modules.job.dto;

/** 技能词频（契约 SkillCountItem）：必备与加分合并，小写归一。 */
public record SkillCountItemResponse(String skill, Integer count) {
}
