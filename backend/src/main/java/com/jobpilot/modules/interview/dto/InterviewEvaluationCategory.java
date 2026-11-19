package com.jobpilot.modules.interview.dto;

/** 分类得分；分类由出题方向的知识体系定义（FR3.6）。 */
public record InterviewEvaluationCategory(String key, String label, Integer score) {
}
