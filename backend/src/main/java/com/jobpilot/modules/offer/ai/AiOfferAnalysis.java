package com.jobpilot.modules.offer.ai;

import java.util.List;

/** Offer 分析的 AI 中间 DTO：不包含 provider / model / basedOn 等本地字段。 */
public record AiOfferAnalysis(
        String tendency,
        List<String> reasons,
        String prospect,
        List<String> risks,
        List<String> sources) {
}
