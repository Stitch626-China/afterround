package com.jobpilot.modules.interview.dto;

import java.util.List;

/** 面试题（主问题 / 追问）；questionIndex 为会话内呈现顺序（FR3.4）。 */
public record InterviewQuestion(
        Integer questionIndex,
        Integer parentIndex,
        String type,
        String content,
        String topicSummary,
        String difficulty,
        String answerStatus,
        String answer,
        Integer score,
        String feedback,
        String referenceAnswer,
        List<String> keyPoints,
        String llmProvider,
        String llmModel) {
}
