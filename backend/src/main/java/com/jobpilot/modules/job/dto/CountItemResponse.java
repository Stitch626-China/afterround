package com.jobpilot.modules.job.dto;

/** 分组计数（契约 CountItem）：薪资区间 / 经验原文 / 岗位方向原文。 */
public record CountItemResponse(String label, Integer count) {
}
