package com.jobpilot.modules.interview.ai;

import java.util.List;

/**
 * 出题 / 追问的 AI 中间 DTO（§6.5）：与业务实体分离，便于本地兜底校验。
 *
 * <p>嫌疑输出由模型生成，字段可能缺失或越界，落库前必须逐项校验。
 */
public record AiInterviewQuestions(List<Item> questions) {

    public record Item(
            String content,
            String topicSummary,
            String difficulty,
            List<String> keyPoints,
            String referenceAnswer) {
    }
}
