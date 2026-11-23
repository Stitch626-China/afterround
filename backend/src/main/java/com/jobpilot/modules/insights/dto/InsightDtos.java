package com.jobpilot.modules.insights.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** 跨场洞察响应（契约 1.10.0）：字段名与 schema 完全一致，比例在分母为 0 时为 null。 */
public final class InsightDtos {

    private InsightDtos() {
    }

    public record InsightSample(int applications, int interviews, int reviews, int confirmedReviews,
                                int offers, int minimum, boolean sufficient) {
    }

    public record InsightSummary(String content, String basedOn) {
    }

    public record InsightTopicOutcome(String topic, String label, int interviewCount, int passCount,
                                      int failCount, int pendingCount, Double failRate, boolean sufficient) {
    }

    public record InsightTagCount(String tag, String label, int count, OffsetDateTime lastSeenAt) {
    }

    public record InsightRoundPassRate(String roundType, String label, int total, int passCount,
                                       int failCount, int pendingCount, Double passRate, boolean sufficient) {
    }

    public record InsightCompanyProfile(Long companyId, String companyName, int interviewCount, int passCount,
                                        int failCount, int pendingCount, List<InsightTagCount> topTopics,
                                        List<InsightTagCount> commonLossTags, boolean sufficient,
                                        OffsetDateTime lastInterviewAt) {
    }

    public record InsightQuestion(String question, int askedCount, OffsetDateTime lastAskedAt) {
    }

    public record InsightFunnel(int applications, int interviews, int offers, int rejected, int withdrawn,
                                Double interviewRate, Double offerRate) {
    }

    public record InsightResumeFunnel(Long resumeId, String fileName, int applications, int interviews, int offers,
                                      int passed, int rejected, Double interviewRate, Double passRate,
                                      boolean sufficient) {
    }

    public record InsightStaleAction(Long id, String action, String status, OffsetDateTime createdAt) {
    }

    public record InsightImprovements(int total, int todo, int inProgress, int verified, int dropped,
                                      Double verificationRate, List<InsightStaleAction> staleActions) {
    }

    public record InsightLossTagDelta(String tag, String label, int currentCount, int previousCount, int delta) {
    }

    public record InsightTrend(int windowDays, int currentReviews, int previousReviews, boolean sufficient,
                               List<InsightLossTagDelta> lossTagDeltas, Double passRateDelta) {
    }

    public record InsightsOverview(InsightSample sample, InsightSummary summary,
                                   List<InsightTopicOutcome> topics, List<InsightTagCount> lossTags,
                                   List<InsightRoundPassRate> roundPassRates,
                                   List<InsightCompanyProfile> companyProfiles,
                                   List<InsightQuestion> topQuestions, InsightFunnel funnel,
                                   List<InsightResumeFunnel> resumeFunnel, InsightImprovements improvements,
                                   InsightTrend trend, String taxonomyVersion, OffsetDateTime generatedAt) {
    }
}
