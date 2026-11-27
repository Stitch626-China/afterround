package com.jobpilot.modules.eval.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.modules.eval.core.EvalMetrics;
import com.jobpilot.modules.eval.dto.EvalCompareCase;
import com.jobpilot.modules.eval.dto.EvalCompareCost;
import com.jobpilot.modules.eval.dto.EvalCompareLatency;
import com.jobpilot.modules.eval.dto.EvalCompareQuality;
import com.jobpilot.modules.eval.dto.EvalCompareRegression;
import com.jobpilot.modules.eval.dto.EvalRunCompare;
import com.jobpilot.modules.eval.dto.EvalRunCompareSide;
import com.jobpilot.modules.eval.entity.LlmEvalBaseline;
import com.jobpilot.modules.eval.entity.LlmEvalRun;
import com.jobpilot.modules.eval.entity.LlmEvalRunCase;
import com.jobpilot.modules.eval.mapper.LlmEvalBaselineMapper;
import com.jobpilot.modules.eval.mapper.LlmEvalRunCaseMapper;
import com.jobpilot.modules.eval.mapper.LlmEvalRunMapper;
import org.springframework.stereotype.Service;

/**
 * 运行 vs 运行 4 维对比：基线只是被标记的运行，可直接把回归运行与历史基线 run 比较。
 * 可比性要求场景集合、Provider、Model、参数、语料 hash、引擎版本一致；提示词是被测变量，不参与判定。
 */
@Service
public class EvalCompareService {

    private static final ObjectMapper M = new ObjectMapper();
    private static final EvalMetrics EMPTY_METRICS = new EvalMetrics(null, null, null, null, null,
            null, null, null, null, null);
    private static final double SCORE_DRIFT_THRESHOLD = 10;
    private static final double TAG_JACCARD_THRESHOLD = 0.7;

    private final LlmEvalRunMapper runMapper;
    private final LlmEvalRunCaseMapper caseMapper;
    private final LlmEvalBaselineMapper baselineMapper;

    public EvalCompareService(LlmEvalRunMapper runMapper, LlmEvalRunCaseMapper caseMapper,
                              LlmEvalBaselineMapper baselineMapper) {
        this.runMapper = runMapper;
        this.caseMapper = caseMapper;
        this.baselineMapper = baselineMapper;
    }

    public EvalRunCompare compare(Long leftRunId, Long rightRunId) {
        LlmEvalRun left = requireRun(leftRunId);
        LlmEvalRun right = requireRun(rightRunId);
        List<String> leftScenes = parseScenes(left.getScenes());
        List<String> rightScenes = parseScenes(right.getScenes());
        List<LlmEvalRunCase> leftRows = rows(leftRunId);
        List<LlmEvalRunCase> rightRows = rows(rightRunId);
        EvalMetrics leftMetrics = metrics(leftRows);
        EvalMetrics rightMetrics = metrics(rightRows);
        String scene = String.join("+", leftScenes);
        EvalRunCompareSide leftSide = side(left, scene, leftMetrics);
        EvalRunCompareSide rightSide = side(right, scene, rightMetrics);

        List<String> reasons = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (!"COMPLETED".equals(left.getStatus()) || Boolean.TRUE.equals(left.getPartial())) {
            reasons.add("左侧运行未完成或为部分重跑");
        }
        if (!"COMPLETED".equals(right.getStatus()) || Boolean.TRUE.equals(right.getPartial())) {
            reasons.add("右侧运行未完成或为部分重跑");
        }
        if (!new TreeSet<>(leftScenes).equals(new TreeSet<>(rightScenes))) {
            reasons.add("场景集合不同：" + leftScenes + " vs " + rightScenes);
        }
        compareSnapshots(left, right, leftScenes, reasons, warnings);
        if (!Objects.equals(params(left.getParams()), params(right.getParams()))) {
            reasons.add("生成参数不同");
        }
        if (!Objects.equals(left.getCorpusHash(), right.getCorpusHash())) {
            reasons.add("语料不同");
        }
        if (!Objects.equals(left.getEngineVersion(), right.getEngineVersion())) {
            reasons.add("断言引擎版本不同");
        }
        List<String> dimensions = applicableDimensions(leftScenes);
        if (!reasons.isEmpty()) {
            return new EvalRunCompare(false, reasons, warnings, List.of(), scene, leftSide, rightSide,
                    null, null, null, null, List.of());
        }
        return new EvalRunCompare(true, reasons, warnings, dimensions, scene, leftSide, rightSide,
                quality(leftMetrics, rightMetrics),
                regression(leftRows, rightRows),
                cost(leftMetrics, rightMetrics),
                latency(leftMetrics, rightMetrics),
                cases(leftRows, rightRows));
    }

    private EvalRunCompareSide side(LlmEvalRun run, String scene, EvalMetrics metrics) {
        Map<String, String[]> models = snapshot(run.getModelSnapshot(), "providerKey", "model");
        Map<String, String[]> prompts = snapshot(run.getPromptSnapshot(), "promptVersion", "promptHash");
        String first = parseScenes(run.getScenes()).stream().findFirst().orElse("");
        String[] model = models.getOrDefault(first, new String[]{"", ""});
        String[] prompt = prompts.getOrDefault(first, new String[]{"", ""});
        LlmEvalBaseline baseline = parseScenes(run.getScenes()).size() == 1
                ? baselineMapper.selectOne(Wrappers.<LlmEvalBaseline>lambdaQuery()
                .eq(LlmEvalBaseline::getRunId, run.getId()).eq(LlmEvalBaseline::getScene, first)) : null;
        return new EvalRunCompareSide(run.getId(), scene, model[0], model[1], prompt[0], prompt[1],
                run.getCorpusHash(), run.getEngineVersion(), params(run.getParams()), metrics,
                baseline == null ? null : baseline.getId(), baseline == null ? null : baseline.getLabel());
    }

    private void compareSnapshots(LlmEvalRun left, LlmEvalRun right, List<String> scenes,
                                  List<String> reasons, List<String> warnings) {
        Map<String, String[]> leftModels = snapshot(left.getModelSnapshot(), "providerKey", "model");
        Map<String, String[]> rightModels = snapshot(right.getModelSnapshot(), "providerKey", "model");
        boolean legacy = false;
        for (String scene : scenes) {
            String[] leftModel = leftModels.getOrDefault(scene, new String[]{"", ""});
            String[] rightModel = rightModels.getOrDefault(scene, new String[]{"", ""});
            if ("legacy".equals(leftModel[0]) || "legacy".equals(rightModel[0])) {
                legacy = true;
            } else if (!Objects.equals(leftModel[0], rightModel[0])) {
                reasons.add(scene + " Provider 不同：" + leftModel[0] + " vs " + rightModel[0]);
            }
            if (!Objects.equals(leftModel[1], rightModel[1])) {
                reasons.add(scene + " 模型不同：" + leftModel[1] + " vs " + rightModel[1]);
            }
        }
        if (legacy) {
            warnings.add("历史基线 Provider 未知，按模型比较");
        }
    }

    private EvalCompareQuality quality(EvalMetrics left, EvalMetrics right) {
        return new EvalCompareQuality(left.l1PassRate(), right.l1PassRate(),
                delta(left.l1PassRate(), right.l1PassRate()),
                delta(left.l2PassRate(), right.l2PassRate()),
                right.l1Failed(), right.l2Failed());
    }

    private EvalCompareRegression regression(List<LlmEvalRunCase> left, List<LlmEvalRunCase> right) {
        Map<String, LlmEvalRunCase> leftMap = byCase(left);
        Map<String, LlmEvalRunCase> rightMap = byCase(right);
        int fixed = 0;
        int failed = 0;
        int drift = 0;
        int tags = 0;
        for (String caseId : union(leftMap, rightMap)) {
            LlmEvalRunCase l = leftMap.get(caseId);
            LlmEvalRunCase r = rightMap.get(caseId);
            if (l != null && r != null) {
                if (!passed(l.getStatus()) && passed(r.getStatus())) {
                    fixed++;
                }
                if (passed(l.getStatus()) && !passed(r.getStatus())) {
                    failed++;
                }
            }
            Double scoreDrift = scoreDrift(l, r);
            if (scoreDrift != null && scoreDrift > SCORE_DRIFT_THRESHOLD) {
                drift++;
            }
            Double jaccard = tagJaccard(l, r);
            if (jaccard != null && jaccard < TAG_JACCARD_THRESHOLD) {
                tags++;
            }
        }
        return new EvalCompareRegression(fixed, failed, drift, tags);
    }

    private List<EvalCompareCase> cases(List<LlmEvalRunCase> left, List<LlmEvalRunCase> right) {
        Map<String, LlmEvalRunCase> leftMap = byCase(left);
        Map<String, LlmEvalRunCase> rightMap = byCase(right);
        List<EvalCompareCase> cases = new ArrayList<>();
        for (String caseId : union(leftMap, rightMap)) {
            LlmEvalRunCase l = leftMap.get(caseId);
            LlmEvalRunCase r = rightMap.get(caseId);
            String leftStatus = l == null ? "SKIPPED" : l.getStatus();
            String rightStatus = r == null ? "SKIPPED" : r.getStatus();
            List<String> changes = new ArrayList<>();
            if (!passed(leftStatus) && passed(rightStatus)) {
                changes.add("FIXED");
            }
            if (passed(leftStatus) && !passed(rightStatus)) {
                changes.add("REGRESSED");
            }
            Double scoreDrift = scoreDrift(l, r);
            if (scoreDrift != null && scoreDrift > SCORE_DRIFT_THRESHOLD) {
                changes.add("SCORE_DRIFT");
            }
            Double jaccard = tagJaccard(l, r);
            if (jaccard != null && jaccard < TAG_JACCARD_THRESHOLD) {
                changes.add("TAG_CHANGED");
            }
            if (changes.isEmpty()) {
                changes.add("UNCHANGED");
            }
            cases.add(new EvalCompareCase(caseId, leftStatus, rightStatus, changes, scoreDrift, jaccard,
                    details(leftStatus, rightStatus, changes, scoreDrift)));
        }
        return cases;
    }

    private static String details(String left, String right, List<String> changes, Double drift) {
        StringBuilder builder = new StringBuilder(left).append(" → ").append(right);
        if (changes.contains("FIXED")) {
            builder.append("，已修复");
        }
        if (changes.contains("REGRESSED")) {
            builder.append("，出现回归");
        }
        if (drift != null && changes.contains("SCORE_DRIFT")) {
            builder.append(String.format(java.util.Locale.ROOT, "，分数漂移 %.1f", drift));
        }
        if (changes.contains("TAG_CHANGED")) {
            builder.append("，标签变化较大");
        }
        return builder.toString();
    }

    private static EvalCompareCost cost(EvalMetrics left, EvalMetrics right) {
        Long leftTokens = left.totalTokens();
        Long rightTokens = right.totalTokens();
        Long delta = leftTokens == null || rightTokens == null ? null : rightTokens - leftTokens;
        return new EvalCompareCost(leftTokens, rightTokens, delta, left.avgTokens(), right.avgTokens());
    }

    private static EvalCompareLatency latency(EvalMetrics left, EvalMetrics right) {
        return new EvalCompareLatency(left.avgElapsedMs(), right.avgElapsedMs(),
                delta(left.avgElapsedMs(), right.avgElapsedMs()),
                left.p95ElapsedMs(), right.p95ElapsedMs(),
                delta(left.p95ElapsedMs(), right.p95ElapsedMs()));
    }

    private static Double delta(Double left, Double right) {
        return left == null || right == null ? null : Math.round((right - left) * 10_000) / 10_000.0;
    }

    private static boolean passed(String status) {
        return "PASS".equals(status) || "GRACEFUL".equals(status);
    }

    private static Double scoreDrift(LlmEvalRunCase left, LlmEvalRunCase right) {
        Map<String, Double> leftScores = scores(left);
        Map<String, Double> rightScores = scores(right);
        Double max = null;
        for (Map.Entry<String, Double> entry : leftScores.entrySet()) {
            Double now = rightScores.get(entry.getKey());
            if (now != null) {
                double drift = Math.abs(now - entry.getValue());
                max = max == null ? drift : Math.max(max, drift);
            }
        }
        return max;
    }

    private static Double tagJaccard(LlmEvalRunCase left, LlmEvalRunCase right) {
        Set<String> leftTags = tags(left);
        Set<String> rightTags = tags(right);
        if (leftTags.isEmpty() && rightTags.isEmpty()) {
            return null;
        }
        Set<String> union = new LinkedHashSet<>(leftTags);
        union.addAll(rightTags);
        Set<String> intersection = new LinkedHashSet<>(leftTags);
        intersection.retainAll(rightTags);
        return Math.round((double) intersection.size() / union.size() * 1_000) / 1_000.0;
    }

    private static Map<String, Double> scores(LlmEvalRunCase row) {
        if (row == null) {
            return Map.of();
        }
        try {
            return M.readValue(row.getScores() == null ? "{}" : row.getScores(),
                    new TypeReference<Map<String, Double>>() {
                    });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static Set<String> tags(LlmEvalRunCase row) {
        Set<String> tags = new LinkedHashSet<>();
        if (row == null) {
            return tags;
        }
        array(row.getTopics()).forEach(node -> tags.add(node.asText()));
        array(row.getLossTags()).forEach(node -> tags.add(node.asText()));
        return tags;
    }

    private List<String> applicableDimensions(List<String> scenes) {
        boolean score = !scenes.isEmpty() && scenes.stream().allMatch(scene -> !"jd-parse".equals(scene));
        boolean tag = !scenes.isEmpty() && scenes.stream().allMatch("review-analyze"::equals);
        List<String> dimensions = new ArrayList<>();
        dimensions.add("QUALITY");
        if (score) {
            dimensions.add("SCORE_DRIFT");
        }
        if (tag) {
            dimensions.add("TAG_CHANGE");
        }
        dimensions.add("COST");
        dimensions.add("LATENCY");
        return dimensions;
    }

    private static Map<String, LlmEvalRunCase> byCase(List<LlmEvalRunCase> rows) {
        Map<String, LlmEvalRunCase> map = new LinkedHashMap<>();
        rows.forEach(row -> map.put(row.getCaseId(), row));
        return map;
    }

    private static Set<String> union(Map<String, LlmEvalRunCase> left, Map<String, LlmEvalRunCase> right) {
        Set<String> ids = new TreeSet<>(left.keySet());
        ids.addAll(right.keySet());
        return ids;
    }

    private List<LlmEvalRunCase> rows(Long runId) {
        return caseMapper.selectList(Wrappers.<LlmEvalRunCase>lambdaQuery()
                .eq(LlmEvalRunCase::getRunId, runId).orderByAsc(LlmEvalRunCase::getId));
    }

    private static EvalMetrics metrics(List<LlmEvalRunCase> rows) {
        EvalMetrics metrics = EvalRunService.metricsOf(rows);
        return metrics == null ? EMPTY_METRICS : metrics;
    }

    private LlmEvalRun requireRun(Long runId) {
        LlmEvalRun run = runMapper.selectById(runId);
        if (run == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "评测运行不存在: " + runId);
        }
        return run;
    }

    private static Map<String, String[]> snapshot(String json, String first, String second) {
        Map<String, String[]> map = new LinkedHashMap<>();
        for (JsonNode node : array(json)) {
            map.put(node.path("scene").asText(), new String[]{node.path(first).asText(),
                    node.path(second).asText()});
        }
        return map;
    }

    private static Map<String, Object> params(String json) {
        try {
            return M.readValue(json == null ? "{}" : json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static List<String> parseScenes(String json) {
        List<String> scenes = new ArrayList<>();
        array(json).forEach(node -> scenes.add(node.asText()));
        return scenes;
    }

    private static JsonNode array(String json) {
        try {
            JsonNode node = M.readTree(json == null ? "[]" : json);
            return node.isArray() ? node : M.createArrayNode();
        } catch (Exception ex) {
            return M.createArrayNode();
        }
    }
}
