package com.jobpilot.modules.llm.dto;

/** LLM 调用场景（FRX.11）；枚举一次性定义，未列入的内部调用走全局默认。 */
public enum LlmScene {

    RESUME_ANALYZE("简历分析"),
    CHAT("模型对话"),
    JOB_MATCH("岗位匹配"),
    INTERVIEW_QUESTION("模拟面试出题"),
    INTERVIEW_EVALUATE("模拟面试评估"),
    OFFER_ANALYZE("Offer 分析"),
    REVIEW_ANALYZE("复盘分析");

    private final String label;

    LlmScene(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
