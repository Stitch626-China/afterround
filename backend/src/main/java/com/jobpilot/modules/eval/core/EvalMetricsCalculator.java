package com.jobpilot.modules.eval.core;

import java.util.Comparator;
import java.util.List;

/** 指标聚合：L1 按用例、L2 按断言条目；PENDING / SKIPPED 不参与统计。 */
public final class EvalMetricsCalculator {

    public record CaseResult(String status, Boolean l1Passed, Integer l2Total, Integer l2Failed,
                             Long tokens, Long elapsedMs) {
    }

    private EvalMetricsCalculator() {
    }

    public static EvalMetrics of(List<CaseResult> results) {
        List<CaseResult> evaluated = results.stream()
                .filter(result -> !"PENDING".equals(result.status()) && !"SKIPPED".equals(result.status()))
                .toList();
        if (evaluated.isEmpty()) {
            return new EvalMetrics(null, null, null, null, null, null, null, null, null, null);
        }
        List<CaseResult> l1 = evaluated.stream().filter(result -> result.l1Passed() != null).toList();
        int l1Total = l1.size();
        int l1Failed = (int) l1.stream().filter(result -> !result.l1Passed()).count();
        int l2Cases = (int) evaluated.stream().filter(result -> result.l2Total() != null).count();
        int l2Total = evaluated.stream().mapToInt(result -> result.l2Total() == null ? 0 : result.l2Total()).sum();
        int l2Failed = evaluated.stream().mapToInt(result -> result.l2Failed() == null ? 0 : result.l2Failed()).sum();
        List<Long> tokens = evaluated.stream().map(CaseResult::tokens).filter(value -> value != null).toList();
        List<Long> elapsed = evaluated.stream().map(CaseResult::elapsedMs).filter(value -> value != null)
                .sorted(Comparator.naturalOrder()).toList();
        Long totalTokens = tokens.isEmpty() ? null : tokens.stream().mapToLong(Long::longValue).sum();
        return new EvalMetrics(l1.isEmpty() ? null : l1Total, l1.isEmpty() ? null : l1Failed,
                l1.isEmpty() ? null : passRate(l1Failed, l1Total),
                l2Cases == 0 ? null : l2Total, l2Cases == 0 ? null : l2Failed,
                l2Cases == 0 ? null : passRate(l2Failed, l2Total),
                totalTokens,
                tokens.isEmpty() ? null : round(totalTokens / (double) tokens.size(), 1),
                elapsed.isEmpty() ? null : round(elapsed.stream().mapToLong(Long::longValue).average().orElse(0), 1),
                elapsed.isEmpty() ? null : (double) elapsed.get((int) Math.ceil(elapsed.size() * 0.95) - 1));
    }

    private static Double passRate(int failed, int total) {
        return total == 0 ? null : round((double) (total - failed) / total, 4);
    }

    private static double round(double value, int scale) {
        double factor = Math.pow(10, scale);
        return Math.round(value * factor) / factor;
    }
}
