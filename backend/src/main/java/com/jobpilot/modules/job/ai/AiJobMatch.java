package com.jobpilot.modules.job.ai;

import java.util.List;

/** 匹配评分的 AI 中间 DTO：只输出三项 0-100 评分与文字建议，总分由本地加权。 */
public record AiJobMatch(
        Integer skillMatch,
        Integer experienceMatch,
        Integer bonusCoverage,
        List<String> gaps,
        List<String> preparationAdvice) {
}
