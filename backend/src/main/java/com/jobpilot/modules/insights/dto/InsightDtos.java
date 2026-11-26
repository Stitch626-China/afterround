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

    public record InsightFunnelGroup(String label, int applications, int interviews, int offers,
                                     Double interviewRate, Double offerRate, boolean sufficient) {
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

    public record InsightEventTopic(String tag, String label) {
    }

    public record InsightEventItem(Long id, Long applicationId, Long companyId, String companyName,
                                   Long jobPositionId, String jobTitle, Integer roundNo, String roundType,
                                   String result, OffsetDateTime interviewTime, Long reviewSessionId,
                                   List<InsightEventTopic> topics) {
    }

    public record InsightsOverview(InsightSample sample, InsightSummary summary,
                                   List<InsightTopicOutcome> topics, List<InsightTagCount> lossTags,
                                   List<InsightRoundPassRate> roundPassRates,
                                   List<InsightCompanyProfile> companyProfiles,
                                   List<InsightQuestion> topQuestions, InsightFunnel funnel,
                                   List<InsightResumeFunnel> resumeFunnel,
                                   List<InsightFunnelGroup> channels, List<InsightFunnelGroup> companyTypes,
                                   InsightImprovements improvements,
                                   InsightTrend trend, String taxonomyVersion, OffsetDateTime generatedAt) {
    }

    /** AI 总结请求（FR5.8）：筛选条件与洞察概览一致，全部可选。 */
    public record InsightsSummaryRequest(Integer days, Long companyId, Long jobPositionId) {
    }

    /** AI 总结响应（FR5.8）：degraded=true 表示已回退本地规则总结。 */
    public record InsightsSummaryResponse(String content, boolean degraded, InsightSample sample,
                                          String provider, String model, OffsetDateTime generatedAt) {
    }
}
