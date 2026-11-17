package com.jobpilot.modules.resume.ai;

/** 简历对比总结的 AI 中间 DTO：只输出一段总结文本，差异事实由本地计算。 */
public record AiResumeComparison(String summary) {
}
