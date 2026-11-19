package com.jobpilot.modules.interview.dto;

/** 面试方向（FR3.1）：由 skills/<key>/skill.meta.yml 定义，新增方向不改代码。 */
public record InterviewDirection(String key, String label, String description) {
}
