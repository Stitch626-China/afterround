package com.jobpilot.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.jobpilot.modules.eval.core.EvalMetrics;
import com.jobpilot.modules.eval.core.EvalMetricsCalculator;
import com.jobpilot.modules.eval.core.EvalMetricsCalculator.CaseResult;
import org.junit.jupiter.api.Test;

/** 指标聚合：L1 按用例、L2 按断言、tokens 与耗时均值 / P95，跳过未执行用例。 */
class EvalMetricsCalculatorTest {

    @Test
    void aggregatesRatesTokensAndP95() {
        List<CaseResult> results = List.of(
                new CaseResult("PASS", true, 5, 0, 100L, 100L),
                new CaseResult("PASS", true, 5, 1, 200L, 200L),
                new CaseResult("FAIL", false, 5, 2, 300L, 300L),
                new CaseResult("SKIPPED", null, null, null, null, null));
        EvalMetrics metrics = EvalMetricsCalculator.of(results);
        assertThat(metrics.l1Total()).isEqualTo(3);
        assertThat(metrics.l1Failed()).isEqualTo(1);
        assertThat(metrics.l1PassRate()).isEqualTo(0.6667);
        assertThat(metrics.l2Total()).isEqualTo(15);
        assertThat(metrics.l2Failed()).isEqualTo(3);
        assertThat(metrics.l2PassRate()).isEqualTo(0.8);
        assertThat(metrics.totalTokens()).isEqualTo(600L);
        assertThat(metrics.avgTokens()).isEqualTo(200.0);
        assertThat(metrics.avgElapsedMs()).isEqualTo(200.0);
        assertThat(metrics.p95ElapsedMs()).isEqualTo(300.0);
    }

    @Test
    void p95UsesNearestRank() {
        List<CaseResult> results = java.util.stream.IntStream.rangeClosed(1, 20)
                .mapToObj(i -> new CaseResult("PASS", true, 1, 0, null, (long) i * 100))
                .toList();
        assertThat(EvalMetricsCalculator.of(results).p95ElapsedMs()).isEqualTo(1900.0);
    }

    @Test
    void allSkippedReturnsNullMetrics() {
        EvalMetrics metrics = EvalMetricsCalculator.of(List.of(new CaseResult("SKIPPED", null, null, null, null, null)));
        assertThat(metrics.l1Total()).isNull();
        assertThat(metrics.totalTokens()).isNull();
        assertThat(metrics.p95ElapsedMs()).isNull();
    }
}
