package com.jobpilot.modules.resume.ai;

import java.util.List;

/**
 * LLM 返回的简历分析原始结构；总分由本地按权重计算，不信任模型自报总分。
 */
public record AiResumeAnalysis(
        Integer technicalDepth,
        Integer projectQuality,
        Integer engineeringAbility,
        Integer expressionStructure,
        Integer jobMatchPotential,
        BasicInfo basicInfo,
        List<Quote> strengths,
        List<Quote> weaknesses,
        List<Suggestion> suggestions,
        List<Career> careerDirections) {

    public record BasicInfo(String name, Double yearsOfExperience, String techDirection) {
    }

    public record Quote(String quote, String comment) {
    }

    public record Suggestion(String original, String improved, String reason) {
    }

    public record Career(String direction, String matchReason, List<String> skillGaps,
                         List<String> interviewFocus) {
    }
}
