package com.jobpilot.modules.review.ai;

import java.util.List;

/** LLM 返回的复盘分析原始结构；保存前由本地做词表过滤、分数钳制与截断。 */
public record AiReviewAnalysis(
        List<Question> questions,
        List<Tag> topics,
        List<Tag> lossTags,
        Summary summary,
        List<Action> actions) {

    public record Question(
            Integer questionIndex,
            String question,
            String answerSummary,
            Integer score,
            List<Dimension> dimensions,
            List<String> topics,
            List<String> lossTags,
            String evidence,
            String feedback,
            String referenceAnswer) {
    }

    public record Dimension(String dimension, Integer score) {
    }

    public record Tag(String tag, Double confidence, String evidence) {
    }

    public record Summary(String overall, List<String> strengths, List<String> improvements,
                          List<String> nextPreparation) {
    }

    public record Action(String action, String category, String reason) {
    }
}
