package com.jobpilot.modules.insights.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;

import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.common.llm.ChatResult;
import com.jobpilot.common.llm.LlmClient;
import com.jobpilot.infrastructure.llm.LlmCallLogger;
import com.jobpilot.infrastructure.llm.ProviderClientRegistry;
import com.jobpilot.modules.insights.dto.InsightDtos.InsightsOverview;
import com.jobpilot.modules.insights.dto.InsightDtos.InsightsSummaryRequest;
import com.jobpilot.modules.insights.mapper.InsightsMapper;
import com.jobpilot.modules.insights.mapper.InsightsRows.ActionStatusRow;
import com.jobpilot.modules.insights.mapper.InsightsRows.CompanyRow;
import com.jobpilot.modules.insights.mapper.InsightsRows.CompanyTagRow;
import com.jobpilot.modules.insights.mapper.InsightsRows.FunnelRow;
import com.jobpilot.modules.insights.mapper.InsightsRows.FunnelGroupRow;
import com.jobpilot.modules.insights.mapper.InsightsRows.InsightEventRow;
import com.jobpilot.modules.insights.mapper.InsightsRows.LossTagRow;
import com.jobpilot.modules.insights.mapper.InsightsRows.QuestionRow;
import com.jobpilot.modules.insights.mapper.InsightsRows.ResumeFunnelRow;
import com.jobpilot.modules.insights.mapper.InsightsRows.ReviewStatsRow;
import com.jobpilot.modules.insights.mapper.InsightsRows.RoundRow;
import com.jobpilot.modules.insights.mapper.InsightsRows.StaleActionRow;
import com.jobpilot.modules.insights.mapper.InsightsRows.TopicOutcomeRow;
import com.jobpilot.modules.insights.prompt.InsightsPromptFactory;
import com.jobpilot.modules.llm.dto.LlmScene;
import com.jobpilot.modules.llm.service.LlmSceneService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 跨场洞察：比例计算、样本保护、趋势差值与参数校验（聚合 SQL 由真实库冒烟覆盖）。 */
@ExtendWith(MockitoExtension.class)
class InsightsServiceTest {

    @Mock
    private InsightsMapper mapper;
    @Mock
    private LlmSceneService sceneService;
    @Mock
    private ProviderClientRegistry registry;
    @Mock
    private LlmCallLogger callLogger;
    @Mock
    private InsightsPromptFactory prompts;
    @Mock
    private LlmClient client;

    private InsightsService service() {
        return new InsightsService(mapper, sceneService, registry, callLogger, prompts);
    }

    private static TopicOutcomeRow topic(String tag, long total, long pass, long fail, long pending) {
        TopicOutcomeRow row = new TopicOutcomeRow();
        row.setTag(tag);
        row.setInterviewCount(total);
        row.setPassCount(pass);
        row.setFailCount(fail);
        row.setPendingCount(pending);
        return row;
    }

    private static LossTagRow loss(String tag, long count) {
        LossTagRow row = new LossTagRow();
        row.setTag(tag);
        row.setTagCount(count);
        row.setLastSeenAt(OffsetDateTime.parse("2026-11-20T10:00:00+08:00"));
        return row;
    }

    private static RoundRow round(String type, long total, long pass, long fail) {
        RoundRow row = new RoundRow();
        row.setRoundType(type);
        row.setTotal(total);
        row.setPassCount(pass);
        row.setFailCount(fail);
        row.setPendingCount(total - pass - fail);
        return row;
    }

    private static ReviewStatsRow stats(long total, long confirmed) {
        ReviewStatsRow row = new ReviewStatsRow();
        row.setTotalReviews(total);
        row.setConfirmedReviews(confirmed);
        return row;
    }

    private void stubEmpty() {
        when(mapper.topQuestions(any(), any(), any(), any())).thenReturn(List.of());
        when(mapper.companyProfiles(any(), any(), any(), any())).thenReturn(List.of());
        when(mapper.companyTopicCounts(any(), any(), any(), any())).thenReturn(List.of());
        when(mapper.companyLossTagCounts(any(), any(), any(), any())).thenReturn(List.of());
        when(mapper.resumeFunnel(any(), any(), any(), any())).thenReturn(List.of());
        when(mapper.actionStatusCounts()).thenReturn(List.of());
        when(mapper.staleActions(any())).thenReturn(List.of());
    }

    private void stubSufficientSample() {
        stubEmpty();
        when(mapper.topicOutcomes(any(), any(), any(), any()))
                .thenReturn(List.of(topic("jvm", 6, 2, 4, 0)));
        when(mapper.lossTagFrequency(any(), any(), any(), any()))
                .thenReturn(List.of(loss("shallow-depth", 4)), List.of(loss("shallow-depth", 4)), List.of());
        when(mapper.roundPassRates(any(), any(), any(), any()))
                .thenReturn(List.of(round("TECH", 8, 6, 2)), List.of(round("TECH", 8, 6, 2)), List.of());
        when(mapper.funnel(any(), any(), any(), any())).thenReturn(funnel(8, 6, 3, 2, 0));
        when(mapper.reviewStats(any(), any(), any(), any())).thenReturn(stats(6, 6), stats(5, 5));
    }

    @Test
    void computesRatesAndGuardsSmallSample() {
        stubEmpty();
        when(mapper.topicOutcomes(any(), any(), any(), any()))
                .thenReturn(List.of(topic("jvm", 3, 1, 2, 0)));
        when(mapper.lossTagFrequency(any(), any(), any(), any()))
                .thenReturn(List.of(loss("shallow-depth", 2)), List.of(loss("shallow-depth", 2)), List.of());
        when(mapper.roundPassRates(any(), any(), any(), any()))
                .thenReturn(List.of(round("TECH", 3, 1, 2)),
                        List.of(round("TECH", 3, 1, 2)), List.of());
        when(mapper.funnel(any(), any(), any(), any())).thenReturn(funnel(10, 4, 2, 3, 1));
        when(mapper.reviewStats(any(), any(), any(), any()))
                .thenReturn(stats(5, 4), stats(0, 0));
        when(mapper.actionStatusCounts()).thenReturn(List.of(action("TODO", 1), action("VERIFIED", 2),
                action("DROPPED", 1)));

        InsightsOverview result = service().overview(90, null, null);

        assertThat(result.sample().confirmedReviews()).isEqualTo(4);
        assertThat(result.sample().sufficient()).isFalse();
        assertThat(result.summary().content()).contains("样本不足");
        assertThat(result.topics()).singleElement().satisfies(item -> {
            assertThat(item.label()).isEqualTo("JVM 与 GC");
            assertThat(item.failRate()).isEqualTo(0.6667);
            assertThat(item.sufficient()).isFalse();
        });
        assertThat(result.roundPassRates()).singleElement().satisfies(item -> {
            assertThat(item.label()).isEqualTo("技术面");
            assertThat(item.passRate()).isEqualTo(0.3333);
        });
        assertThat(result.funnel().interviewRate()).isEqualTo(0.4);
        assertThat(result.funnel().offerRate()).isEqualTo(0.2);
        assertThat(result.improvements().verificationRate()).isEqualTo(0.6667);
        assertThat(result.trend().sufficient()).isFalse();
        assertThat(result.trend().passRateDelta()).isNull();
        assertThat(result.topics()).hasSize(1);
    }

    @Test
    void computesTrendAndSummaryWhenSampleSufficient() {
        stubEmpty();
        when(mapper.topicOutcomes(any(), any(), any(), any()))
                .thenReturn(List.of(topic("jvm", 6, 2, 4, 0)));
        when(mapper.lossTagFrequency(any(), any(), any(), any()))
                .thenReturn(List.of(loss("shallow-depth", 4), loss("unclear-expression", 2)),
                        List.of(loss("shallow-depth", 4), loss("unclear-expression", 2)),
                        List.of(loss("shallow-depth", 1)));
        when(mapper.roundPassRates(any(), any(), any(), any()))
                .thenReturn(List.of(round("TECH", 8, 6, 2)),
                        List.of(round("TECH", 8, 6, 2)), List.of(round("TECH", 8, 4, 4)));
        when(mapper.funnel(any(), any(), any(), any())).thenReturn(funnel(8, 6, 3, 2, 0));
        when(mapper.reviewStats(any(), any(), any(), any()))
                .thenReturn(stats(6, 6), stats(5, 5));
        when(mapper.actionStatusCounts()).thenReturn(List.of(action("TODO", 1), action("IN_PROGRESS", 1),
                action("VERIFIED", 3), action("DROPPED", 1)));
        when(mapper.staleActions(any())).thenReturn(List.of());

        InsightsOverview result = service().overview(30, 1L, 2L);

        assertThat(result.sample().sufficient()).isTrue();
        assertThat(result.summary().content())
                .contains("深度不足")
                .contains("通过率最低")
                .contains("验证率 60%");
        assertThat(result.trend().sufficient()).isTrue();
        assertThat(result.trend().lossTagDeltas()).first().satisfies(delta -> {
            assertThat(delta.tag()).isEqualTo("shallow-depth");
            assertThat(delta.label()).isEqualTo("深度不足");
            assertThat(delta.delta()).isEqualTo(3);
        });
        assertThat(result.trend().passRateDelta()).isEqualTo(0.25);
    }

    @Test
    void summarizeFallsBackToLocalWithoutConfirmedReviews() {
        stubEmpty();
        when(mapper.topicOutcomes(any(), any(), any(), any())).thenReturn(List.of());
        when(mapper.lossTagFrequency(any(), any(), any(), any())).thenReturn(List.of(), List.of(), List.of());
        when(mapper.roundPassRates(any(), any(), any(), any())).thenReturn(List.of(), List.of(), List.of());
        when(mapper.funnel(any(), any(), any(), any())).thenReturn(funnel(0, 0, 0, 0, 0));
        when(mapper.reviewStats(any(), any(), any(), any())).thenReturn(stats(0, 0), stats(0, 0));

        var result = service().summarize(new InsightsSummaryRequest(90, null, null));

        assertThat(result.degraded()).isTrue();
        assertThat(result.provider()).isNull();
        assertThat(result.content()).contains("暂无已确认的复盘分析");
    }

    @Test
    void summarizeCallsModelAndLogsCall() {
        stubSufficientSample();
        when(sceneService.resolve(LlmScene.REVIEW_ANALYZE))
                .thenReturn(new LlmSceneService.SceneModel("corp", "flash"));
        when(registry.get("corp")).thenReturn(client);
        when(prompts.system()).thenReturn("system");
        when(prompts.user(anyInt(), any())).thenReturn("user");
        when(client.chat(eq("flash"), any())).thenReturn(new ChatResult("整体在变好。", "flash", 10, 20, 30));

        var result = service().summarize(new InsightsSummaryRequest(30, 1L, 2L));

        assertThat(result.degraded()).isFalse();
        assertThat(result.content()).isEqualTo("整体在变好。");
        assertThat(result.provider()).isEqualTo("corp");
        assertThat(result.model()).isEqualTo("flash");
        verify(callLogger).log(eq("INSIGHTS_SUMMARY"), eq("corp"), eq("flash"), eq(10), eq(20), eq(30),
                anyLong(), eq(true), isNull());
    }

    @Test
    void summarizeFallsBackWhenModelFails() {
        stubSufficientSample();
        when(sceneService.resolve(LlmScene.REVIEW_ANALYZE))
                .thenThrow(BizException.of(ErrorCode.BAD_REQUEST, "未配置模型"));

        var result = service().summarize(new InsightsSummaryRequest(30, null, null));

        assertThat(result.degraded()).isTrue();
        assertThat(result.content()).contains("最常出现的失分类型");
        verify(callLogger).log(eq("INSIGHTS_SUMMARY"), isNull(), isNull(), isNull(), isNull(), isNull(),
                anyLong(), eq(false), eq("未配置模型"));
    }

    @Test
    void rejectsDaysOutOfRange() {
        assertThatThrownBy(() -> service().overview(6, null, null))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).errorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void mapsInterviewEventsWithTopicsAndForwardsFilters() {
        InsightEventRow row = new InsightEventRow();
        row.setId(11L);
        row.setApplicationId(3L);
        row.setCompanyId(9L);
        row.setCompanyName("字节跳动");
        row.setJobPositionId(4L);
        row.setJobTitle("Java 后端");
        row.setRoundNo(2);
        row.setRoundType("TECH");
        row.setResult("FAIL");
        row.setInterviewTime(OffsetDateTime.parse("2026-11-20T10:00:00+08:00"));
        row.setReviewSessionId(7L);
        row.setAnalysisJson("{\"topics\":[{\"tag\":\"jvm\"},{\"tag\":\"unknown-topic\"},{\"tag\":\"\"}]}");
        when(mapper.interviewEventCount(any(), any(), any(), any(), any(), any(), any())).thenReturn(1L);
        when(mapper.interviewEvents(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(row));

        var result = service().events(90, 1L, 2L, "FAIL", "TECH", "jvm", 2, 10);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(10);
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.companyName()).isEqualTo("字节跳动");
            assertThat(item.reviewSessionId()).isEqualTo(7L);
            assertThat(item.topics()).extracting("tag").containsExactly("jvm", "unknown-topic");
            assertThat(item.topics().get(0).label()).isEqualTo("JVM 与 GC");
            assertThat(item.topics().get(1).label()).isEqualTo("unknown-topic");
        });
        verify(mapper).interviewEvents(any(), any(), eq(1L), eq(2L), eq("FAIL"), eq("TECH"), eq("jvm"),
                eq(10), eq(10));
    }

    @Test
    void returnsEmptyEventPageWithoutQueryingRows() {
        when(mapper.interviewEventCount(any(), any(), any(), any(), any(), any(), any())).thenReturn(0L);

        var result = service().events(90, null, null, null, null, null, null, 500);

        assertThat(result.items()).isEmpty();
        assertThat(result.total()).isZero();
        assertThat(result.size()).isEqualTo(100);
    }

    @Test
    void mapsCompanyProfilesResumeFunnelAndStaleActions() {
        when(mapper.topicOutcomes(any(), any(), any(), any())).thenReturn(List.of());
        when(mapper.lossTagFrequency(any(), any(), any(), any())).thenReturn(List.of());
        when(mapper.roundPassRates(any(), any(), any(), any())).thenReturn(List.of());
        when(mapper.topQuestions(any(), any(), any(), any())).thenReturn(List.of(question("Explain JVM GC", 2)));
        when(mapper.companyProfiles(any(), any(), any(), any())).thenReturn(List.of(company(9L, "字节跳动",
                3, 1, 2)));
        when(mapper.companyTopicCounts(any(), any(), any(), any())).thenReturn(List.of(companyTag(9L, "jvm", 2)));
        when(mapper.companyLossTagCounts(any(), any(), any(), any()))
                .thenReturn(List.of(companyTag(9L, "shallow-depth", 1)));
        when(mapper.funnel(any(), any(), any(), any())).thenReturn(funnel(2, 1, 0, 0, 0));
        when(mapper.resumeFunnel(any(), any(), any(), any())).thenReturn(List.of(resume(3L, "v3.pdf",
                2, 1, 0, 0, 0)));
        when(mapper.reviewStats(any(), any(), any(), any())).thenReturn(stats(1, 1));
        when(mapper.actionStatusCounts()).thenReturn(List.of(action("TODO", 1)));
        when(mapper.staleActions(any())).thenReturn(List.of(stale(7L, "补 JVM 笔记")));

        InsightsOverview result = service().overview(90, null, null);

        assertThat(result.companyProfiles()).singleElement().satisfies(item -> {
            assertThat(item.companyName()).isEqualTo("字节跳动");
            assertThat(item.interviewCount()).isEqualTo(3);
            assertThat(item.sufficient()).isFalse();
            assertThat(item.topTopics()).first().satisfies(tag -> {
                assertThat(tag.tag()).isEqualTo("jvm");
                assertThat(tag.label()).isEqualTo("JVM 与 GC");
                assertThat(tag.count()).isEqualTo(2);
            });
            assertThat(item.commonLossTags()).first()
                    .satisfies(tag -> assertThat(tag.label()).isEqualTo("深度不足"));
        });
        assertThat(result.resumeFunnel()).singleElement().satisfies(item -> {
            assertThat(item.fileName()).isEqualTo("v3.pdf");
            assertThat(item.interviewRate()).isEqualTo(0.5);
            assertThat(item.passRate()).isNull();
            assertThat(item.sufficient()).isFalse();
        });
        assertThat(result.topQuestions()).singleElement()
                .satisfies(item -> assertThat(item.askedCount()).isEqualTo(2));
        assertThat(result.improvements().staleActions()).singleElement().satisfies(item -> {
            assertThat(item.action()).isEqualTo("补 JVM 笔记");
            assertThat(item.status()).isEqualTo("TODO");
        });
    }

    @Test
    void mapsChannelAndCompanyTypeFunnels() {
        when(mapper.channelFunnel(any(), any(), any(), any()))
                .thenReturn(List.of(funnelGroup("BOSS", 10, 6, 2)));
        when(mapper.companyTypeFunnel(any(), any(), any(), any()))
                .thenReturn(List.of(funnelGroup("企业服务", 4, 1, 0)));

        var result = service().overview(90, null, null);

        assertThat(result.channels()).singleElement().satisfies(item -> {
            assertThat(item.label()).isEqualTo("BOSS");
            assertThat(item.interviewRate()).isEqualTo(0.6);
            assertThat(item.offerRate()).isEqualTo(0.2);
            assertThat(item.sufficient()).isTrue();
        });
        assertThat(result.companyTypes()).singleElement().satisfies(item -> {
            assertThat(item.label()).isEqualTo("企业服务");
            assertThat(item.interviewRate()).isEqualTo(0.25);
            assertThat(item.offerRate()).isZero();
            assertThat(item.sufficient()).isFalse();
        });
    }

    private static FunnelGroupRow funnelGroup(String label, long applications, long interviews, long offers) {
        FunnelGroupRow row = new FunnelGroupRow();
        row.setLabel(label);
        row.setApplications(applications);
        row.setInterviews(interviews);
        row.setOffers(offers);
        return row;
    }

    private static FunnelRow funnel(long applications, long interviews, long offers, long rejected, long withdrawn) {
        FunnelRow row = new FunnelRow();
        row.setApplications(applications);
        row.setInterviews(interviews);
        row.setOffers(offers);
        row.setRejected(rejected);
        row.setWithdrawn(withdrawn);
        return row;
    }

    private static ActionStatusRow action(String status, long count) {
        ActionStatusRow row = new ActionStatusRow();
        row.setStatus(status);
        row.setActionCount(count);
        return row;
    }

    private static CompanyRow company(Long id, String name, long total, long pass, long fail) {
        CompanyRow row = new CompanyRow();
        row.setCompanyId(id);
        row.setCompanyName(name);
        row.setInterviewCount(total);
        row.setPassCount(pass);
        row.setFailCount(fail);
        row.setPendingCount(total - pass - fail);
        row.setLastInterviewAt(OffsetDateTime.parse("2026-11-20T10:00:00+08:00"));
        return row;
    }

    private static CompanyTagRow companyTag(Long companyId, String tag, long count) {
        CompanyTagRow row = new CompanyTagRow();
        row.setCompanyId(companyId);
        row.setTag(tag);
        row.setTagCount(count);
        return row;
    }

    private static QuestionRow question(String text, long count) {
        QuestionRow row = new QuestionRow();
        row.setQuestion(text);
        row.setAskedCount(count);
        row.setLastAskedAt(OffsetDateTime.parse("2026-11-20T10:00:00+08:00"));
        return row;
    }

    private static ResumeFunnelRow resume(Long id, String fileName, long applications, long interviews, long offers,
                                          long passed, long rejected) {
        ResumeFunnelRow row = new ResumeFunnelRow();
        row.setResumeId(id);
        row.setFileName(fileName);
        row.setApplications(applications);
        row.setInterviews(interviews);
        row.setOffers(offers);
        row.setPassed(passed);
        row.setRejected(rejected);
        return row;
    }

    private static StaleActionRow stale(Long id, String action) {
        StaleActionRow row = new StaleActionRow();
        row.setId(id);
        row.setAction(action);
        row.setStatus("TODO");
        row.setCreatedAt(OffsetDateTime.parse("2026-11-01T10:00:00+08:00"));
        return row;
    }
}
