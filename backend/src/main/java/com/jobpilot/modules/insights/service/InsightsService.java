package com.jobpilot.modules.insights.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.modules.insights.dto.InsightDtos.InsightCompanyProfile;
import com.jobpilot.modules.insights.dto.InsightDtos.InsightFunnel;
import com.jobpilot.modules.insights.dto.InsightDtos.InsightImprovements;
import com.jobpilot.modules.insights.dto.InsightDtos.InsightLossTagDelta;
import com.jobpilot.modules.insights.dto.InsightDtos.InsightQuestion;
import com.jobpilot.modules.insights.dto.InsightDtos.InsightResumeFunnel;
import com.jobpilot.modules.insights.dto.InsightDtos.InsightRoundPassRate;
import com.jobpilot.modules.insights.dto.InsightDtos.InsightSample;
import com.jobpilot.modules.insights.dto.InsightDtos.InsightStaleAction;
import com.jobpilot.modules.insights.dto.InsightDtos.InsightSummary;
import com.jobpilot.modules.insights.dto.InsightDtos.InsightTagCount;
import com.jobpilot.modules.insights.dto.InsightDtos.InsightTopicOutcome;
import com.jobpilot.modules.insights.dto.InsightDtos.InsightTrend;
import com.jobpilot.modules.insights.dto.InsightDtos.InsightsOverview;
import com.jobpilot.modules.insights.mapper.InsightsMapper;
import com.jobpilot.modules.insights.mapper.InsightsRows.ActionStatusRow;
import com.jobpilot.modules.insights.mapper.InsightsRows.CompanyRow;
import com.jobpilot.modules.insights.mapper.InsightsRows.CompanyTagRow;
import com.jobpilot.modules.insights.mapper.InsightsRows.FunnelRow;
import com.jobpilot.modules.insights.mapper.InsightsRows.LossTagRow;
import com.jobpilot.modules.insights.mapper.InsightsRows.QuestionRow;
import com.jobpilot.modules.insights.mapper.InsightsRows.ResumeFunnelRow;
import com.jobpilot.modules.insights.mapper.InsightsRows.ReviewStatsRow;
import com.jobpilot.modules.insights.mapper.InsightsRows.RoundRow;
import com.jobpilot.modules.insights.mapper.InsightsRows.StaleActionRow;
import com.jobpilot.modules.insights.mapper.InsightsRows.TopicOutcomeRow;
import com.jobpilot.modules.review.taxonomy.ReviewTaxonomyCatalog;
import org.springframework.stereotype.Service;

/**
 * 跨场归因（FR5.5 ~ FR5.9）：SQL 聚合已确认复盘 + 事件 + 投递 + Offer + 改进行动。
 *
 * <p>样本保护：已确认复盘少于 {@link #MINIMUM_SAMPLE} 份时只返回原始统计并标记
 * {@code sufficient = false}，总结只提示样本不足，不输出因果结论。
 */
@Service
public class InsightsService {

    static final int MINIMUM_SAMPLE = 5;
    static final int STALE_DAYS = 14;
    static final String SUMMARY_BASED_ON = "LOCAL_AGGREGATION";

    private static final Map<String, String> ROUND_LABELS = Map.of(
            "HR", "HR 面", "TECH", "技术面", "CROSS", "交叉面",
            "DIRECTOR", "总监面", "FINAL", "终面", "OTHER", "其他");

    private final InsightsMapper mapper;

    public InsightsService(InsightsMapper mapper) {
        this.mapper = mapper;
    }

    public InsightsOverview overview(int days, Long companyId, Long jobPositionId) {
        if (days < 7 || days > 365) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "days 需在 7~365 之间");
        }
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime from = now.minusDays(days);
        OffsetDateTime previousFrom = from.minusDays(days);

        List<InsightTopicOutcome> topics = topics(mapper.topicOutcomes(from, now, companyId, jobPositionId));
        List<InsightTagCount> lossTags = lossTags(mapper.lossTagFrequency(from, now, companyId, jobPositionId));
        List<InsightRoundPassRate> rounds = rounds(mapper.roundPassRates(from, now, companyId, jobPositionId));
        List<InsightCompanyProfile> companies = companies(
                mapper.companyProfiles(from, now, companyId, jobPositionId),
                mapper.companyTopicCounts(from, now, companyId, jobPositionId),
                mapper.companyLossTagCounts(from, now, companyId, jobPositionId));
        List<InsightQuestion> questions = mapper.topQuestions(from, now, companyId, jobPositionId).stream()
                .map(row -> new InsightQuestion(row.getQuestion(), n(row.getAskedCount()), row.getLastAskedAt()))
                .toList();
        InsightFunnel funnel = funnel(mapper.funnel(from, now, companyId, jobPositionId));
        List<InsightResumeFunnel> resumeFunnel = mapper.resumeFunnel(from, now, companyId, jobPositionId).stream()
                .map(this::resumeFunnel)
                .toList();
        InsightImprovements improvements = improvements(mapper.actionStatusCounts(),
                mapper.staleActions(now.minusDays(STALE_DAYS)));

        ReviewStatsRow stats = mapper.reviewStats(from, now, companyId, jobPositionId);
        int reviews = stats == null ? 0 : n(stats.getTotalReviews());
        int confirmed = stats == null ? 0 : n(stats.getConfirmedReviews());
        int interviews = rounds.stream().mapToInt(InsightRoundPassRate::total).sum();
        InsightSample sample = new InsightSample(funnel.applications(), interviews, reviews, confirmed,
                funnel.offers(), MINIMUM_SAMPLE, confirmed >= MINIMUM_SAMPLE);

        InsightTrend trend = trend(days, from, now, previousFrom, confirmed, companyId, jobPositionId);
        return new InsightsOverview(sample, summary(sample, lossTags, rounds, topics, improvements),
                topics, lossTags, rounds, companies, questions, funnel, resumeFunnel, improvements,
                trend, ReviewTaxonomyCatalog.TAXONOMY_VERSION, now);
    }

    private List<InsightTopicOutcome> topics(List<TopicOutcomeRow> rows) {
        return rows.stream().map(row -> {
            int pass = n(row.getPassCount());
            int fail = n(row.getFailCount());
            int total = n(row.getInterviewCount());
            return new InsightTopicOutcome(row.getTag(), topicLabel(row.getTag()), total, pass, fail,
                    n(row.getPendingCount()), rate(fail, pass + fail), total >= MINIMUM_SAMPLE);
        }).toList();
    }

    private List<InsightTagCount> lossTags(List<LossTagRow> rows) {
        return rows.stream().map(row -> new InsightTagCount(row.getTag(), lossLabel(row.getTag()),
                n(row.getTagCount()), row.getLastSeenAt())).toList();
    }

    private List<InsightRoundPassRate> rounds(List<RoundRow> rows) {
        return rows.stream().map(row -> {
            int pass = n(row.getPassCount());
            int fail = n(row.getFailCount());
            int total = n(row.getTotal());
            return new InsightRoundPassRate(row.getRoundType(),
                    ROUND_LABELS.getOrDefault(row.getRoundType(), row.getRoundType()),
                    total, pass, fail, n(row.getPendingCount()), rate(pass, pass + fail), total >= MINIMUM_SAMPLE);
        }).toList();
    }

    private List<InsightCompanyProfile> companies(List<CompanyRow> rows, List<CompanyTagRow> topicRows,
                                                  List<CompanyTagRow> lossRows) {
        Map<Long, List<CompanyTagRow>> topicMap = topicRows.stream()
                .collect(Collectors.groupingBy(CompanyTagRow::getCompanyId));
        Map<Long, List<CompanyTagRow>> lossMap = lossRows.stream()
                .collect(Collectors.groupingBy(CompanyTagRow::getCompanyId));
        return rows.stream().map(row -> {
            int total = n(row.getInterviewCount());
            return new InsightCompanyProfile(row.getCompanyId(), row.getCompanyName(), total,
                    n(row.getPassCount()), n(row.getFailCount()), n(row.getPendingCount()),
                    companyTags(topicMap.get(row.getCompanyId()), true),
                    companyTags(lossMap.get(row.getCompanyId()), false),
                    total >= MINIMUM_SAMPLE, row.getLastInterviewAt());
        }).toList();
    }

    private List<InsightTagCount> companyTags(List<CompanyTagRow> rows, boolean topic) {
        if (rows == null) {
            return List.of();
        }
        return rows.stream().map(row -> new InsightTagCount(row.getTag(),
                topic ? topicLabel(row.getTag()) : lossLabel(row.getTag()), n(row.getTagCount()), null)).toList();
    }

    private InsightFunnel funnel(FunnelRow row) {
        if (row == null) {
            return new InsightFunnel(0, 0, 0, 0, 0, null, null);
        }
        int applications = n(row.getApplications());
        int interviews = n(row.getInterviews());
        int offers = n(row.getOffers());
        return new InsightFunnel(applications, interviews, offers, n(row.getRejected()), n(row.getWithdrawn()),
                rate(interviews, applications), rate(offers, applications));
    }

    private InsightResumeFunnel resumeFunnel(ResumeFunnelRow row) {
        int applications = n(row.getApplications());
        int passed = n(row.getPassed());
        int rejected = n(row.getRejected());
        return new InsightResumeFunnel(row.getResumeId(), row.getFileName(), applications,
                n(row.getInterviews()), n(row.getOffers()), passed, rejected,
                rate(n(row.getInterviews()), applications), rate(passed, passed + rejected),
                applications >= MINIMUM_SAMPLE);
    }

    private InsightImprovements improvements(List<ActionStatusRow> rows, List<StaleActionRow> staleRows) {
        Map<String, Integer> counts = rows.stream().collect(Collectors.toMap(ActionStatusRow::getStatus,
                row -> n(row.getActionCount()), Integer::sum, LinkedHashMap::new));
        int todo = counts.getOrDefault("TODO", 0);
        int inProgress = counts.getOrDefault("IN_PROGRESS", 0);
        int verified = counts.getOrDefault("VERIFIED", 0);
        int dropped = counts.getOrDefault("DROPPED", 0);
        int total = todo + inProgress + verified + dropped;
        int expected = total - dropped;
        List<InsightStaleAction> stale = staleRows.stream()
                .map(row -> new InsightStaleAction(row.getId(), row.getAction(), row.getStatus(), row.getCreatedAt()))
                .toList();
        return new InsightImprovements(total, todo, inProgress, verified, dropped,
                rate(verified, expected), stale);
    }

    private InsightTrend trend(int days, OffsetDateTime from, OffsetDateTime to, OffsetDateTime previousFrom,
                               int currentReviews, Long companyId, Long jobPositionId) {
        ReviewStatsRow previousStats = mapper.reviewStats(previousFrom, from, companyId, jobPositionId);
        int previousReviews = previousStats == null ? 0 : n(previousStats.getConfirmedReviews());
        boolean sufficient = currentReviews >= MINIMUM_SAMPLE && previousReviews >= MINIMUM_SAMPLE;

        Map<String, Integer> current = countMap(mapper.lossTagFrequency(from, to, companyId, jobPositionId));
        Map<String, Integer> previous = countMap(mapper.lossTagFrequency(previousFrom, from,
                companyId, jobPositionId));
        Set<String> tags = new LinkedHashSet<>(current.keySet());
        tags.addAll(previous.keySet());
        List<InsightLossTagDelta> deltas = tags.stream()
                .map(tag -> new InsightLossTagDelta(tag, lossLabel(tag), current.getOrDefault(tag, 0),
                        previous.getOrDefault(tag, 0), current.getOrDefault(tag, 0) - previous.getOrDefault(tag, 0)))
                .sorted(Comparator.comparingInt((InsightLossTagDelta delta) -> Math.abs(delta.delta())).reversed()
                        .thenComparing(InsightLossTagDelta::tag))
                .limit(8)
                .toList();

        Double currentPass = passRate(mapper.roundPassRates(from, to, companyId, jobPositionId));
        Double previousPass = passRate(mapper.roundPassRates(previousFrom, from, companyId, jobPositionId));
        Double delta = sufficient && currentPass != null && previousPass != null
                ? round4(currentPass - previousPass) : null;
        return new InsightTrend(days, currentReviews, previousReviews, sufficient, deltas, delta);
    }

    private Map<String, Integer> countMap(List<LossTagRow> rows) {
        return rows.stream().collect(Collectors.toMap(LossTagRow::getTag,
                row -> n(row.getTagCount()), Integer::sum, LinkedHashMap::new));
    }

    private Double passRate(List<RoundRow> rows) {
        int pass = rows.stream().mapToInt(row -> n(row.getPassCount())).sum();
        int fail = rows.stream().mapToInt(row -> n(row.getFailCount())).sum();
        return rate(pass, pass + fail);
    }

    private InsightSummary summary(InsightSample sample, List<InsightTagCount> lossTags,
                                   List<InsightRoundPassRate> rounds, List<InsightTopicOutcome> topics,
                                   InsightImprovements improvements) {
        if (sample.confirmedReviews() == 0) {
            return new InsightSummary("暂无已确认的复盘分析；完成一场复盘并确认分析后，这里会出现跨场归因。",
                    SUMMARY_BASED_ON);
        }
        if (!sample.sufficient()) {
            return new InsightSummary("已确认复盘 " + sample.confirmedReviews()
                    + " 份，少于 " + MINIMUM_SAMPLE + " 份，样本不足，暂不输出因果结论；以下仅展示原始统计。",
                    SUMMARY_BASED_ON);
        }
        List<String> parts = new ArrayList<>();
        lossTags.stream().findFirst().ifPresent(tag ->
                parts.add("最常出现的失分类型是「" + tag.label() + "」（" + tag.count() + " 场）"));
        rounds.stream().filter(row -> row.total() >= MINIMUM_SAMPLE && row.passRate() != null)
                .min(Comparator.comparingDouble(InsightRoundPassRate::passRate))
                .ifPresent(row -> parts.add("「" + row.label() + "」通过率最低（" + pct(row.passRate()) + "）"));
        topics.stream().filter(row -> row.sufficient() && row.failCount() > 0 && row.failRate() != null)
                .max(Comparator.comparingDouble(InsightTopicOutcome::failRate))
                .ifPresent(row -> parts.add("知识点「" + row.label() + "」出现 " + row.interviewCount()
                        + " 场，失败 " + row.failCount() + " 场"));
        if (improvements.verificationRate() != null) {
            parts.add("改进行动验证率 " + pct(improvements.verificationRate())
                    + "（已验证 " + improvements.verified() + " 条）");
        }
        if (parts.isEmpty()) {
            parts.add("已确认 " + sample.confirmedReviews() + " 份复盘，暂未出现集中失分模式");
        }
        return new InsightSummary(String.join("；", parts) + "。", SUMMARY_BASED_ON);
    }

    private static String topicLabel(String tag) {
        String label = ReviewTaxonomyCatalog.topicLabel(tag);
        return label == null ? tag : label;
    }

    private static String lossLabel(String tag) {
        String label = ReviewTaxonomyCatalog.lossLabel(tag);
        return label == null ? tag : label;
    }

    private static int n(Long value) {
        return value == null ? 0 : value.intValue();
    }

    private static Double rate(int numerator, int denominator) {
        return denominator == 0 ? null : round4((double) numerator / denominator);
    }

    private static double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private static String pct(double value) {
        return Math.round(value * 100) + "%";
    }
}
