package com.jobpilot.modules.interview.ai;

import java.util.List;

/** 评估的 AI 中间 DTO：只包含模型输出字段，总分与跳过题计分由本地计算。 */
public record AiInterviewEvaluation(
        List<QuestionScore> questions,
        List<CategoryScore> categories,
        List<String> strengths,
        List<String> improvements,
        String summary) {

    public record QuestionScore(Integer questionIndex, Integer score, String feedback, String referenceAnswer) {
    }

    public record CategoryScore(String key, String label, Integer score) {
    }
}
