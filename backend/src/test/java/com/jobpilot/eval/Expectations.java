package com.jobpilot.eval;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 断言引擎：L1 硬断言、L2 质量断言、L3 稳定性。
 *
 * <p>只依赖 AI 中间 DTO 转换后的 JSON，不接触数据库与 Spring 容器。
 */
public final class Expectations {

    public record Result(String layer, String name, boolean passed, String detail) {
    }

    private static final Set<String> REF_FIELDS = Set.of("quote", "evidence", "original");
    private static final List<String> RESUME_DIMENSIONS = List.of("technicalDepth", "projectQuality",
            "engineeringAbility", "expressionStructure", "jobMatchPotential");
    private static final double[] WEIGHTS = {0.30, 0.25, 0.20, 0.15, 0.10};

    private Expectations() {
    }

    public static List<Result> evaluate(EvalCase evalCase, JsonNode actual) {
        List<Result> results = new ArrayList<>();
        requiredFields(evalCase, actual, results);
        scoreRange(evalCase, actual, results);
        quotes(evalCase, actual, results);
        maxItems(evalCase, actual, results);
        overallFormula(evalCase, actual, results);
        topics(evalCase, actual, results);
        lossTags(evalCase, actual, results);
        mention(evalCase, actual, results);
        forbiddenTags(evalCase, actual, results);
        skillsMin(evalCase, actual, results);
        return results;
    }

    public static List<Result> stability(EvalCase evalCase, JsonNode first, JsonNode second) {
        List<Result> results = new ArrayList<>();
        int maxDrift = evalCase.intValue("scoreDriftMax", 5);
        double drift = 0;
        for (String path : scorePaths(evalCase.scene())) {
            List<JsonNode> left = collect(first, path);
            List<JsonNode> right = collect(second, path);
            for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
                if (left.get(i).isNumber() && right.get(i).isNumber()) {
                    drift = Math.max(drift, Math.abs(left.get(i).asDouble() - right.get(i).asDouble()));
                }
            }
        }
        results.add(new Result("L3", "scoreDriftMax", drift <= maxDrift, "drift=" + drift + ", max=" + maxDrift));

        double minJaccard = evalCase.expect().path("tagJaccardMin").asDouble(0.7);
        Set<String> firstTags = allTags(first);
        Set<String> secondTags = allTags(second);
        Set<String> union = new LinkedHashSet<>(firstTags);
        union.addAll(secondTags);
        Set<String> intersection = new LinkedHashSet<>(firstTags);
        intersection.retainAll(secondTags);
        double jaccard = union.isEmpty() ? 1.0 : (double) intersection.size() / union.size();
        results.add(new Result("L3", "tagJaccardMin", jaccard >= minJaccard,
                String.format(Locale.ROOT, "jaccard=%.2f, min=%.2f", jaccard, minJaccard)));
        return results;
    }

    /** 简历五维加权总分；字段缺失时返回 null。 */
    public static Double overall(JsonNode actual) {
        double total = 0;
        for (int i = 0; i < RESUME_DIMENSIONS.size(); i++) {
            JsonNode node = actual.path(RESUME_DIMENSIONS.get(i));
            if (!node.isNumber()) {
                return null;
            }
            total += node.asDouble() * WEIGHTS[i];
        }
        return (double) Math.round(total);
    }

    static List<JsonNode> collect(JsonNode root, String path) {
        List<JsonNode> out = new ArrayList<>();
        collect(root, path.split("\\."), 0, out);
        return out;
    }

    private static void collect(JsonNode node, String[] parts, int index, List<JsonNode> out) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (index == parts.length) {
            out.add(node);
            return;
        }
        String part = parts[index];
        boolean array = part.endsWith("[]");
        String field = array ? part.substring(0, part.length() - 2) : part;
        JsonNode next = field.isEmpty() ? node : node.get(field);
        if (next == null || next.isNull()) {
            return;
        }
        if (array && next.isArray()) {
            next.forEach(item -> collect(item, parts, index + 1, out));
        } else if (!array) {
            collect(next, parts, index + 1, out);
        }
    }

    private static void requiredFields(EvalCase evalCase, JsonNode actual, List<Result> results) {
        evalCase.terms("requiredFields").forEach(path -> results.add(new Result("L1", "requiredFields:" + path,
                !collect(actual, path).isEmpty(), "缺失 " + path)));
    }

    private static void scoreRange(EvalCase evalCase, JsonNode actual, List<Result> results) {
        evalCase.expect().path("scoreRange").fields().forEachRemaining(entry -> {
            List<JsonNode> values = collect(actual, entry.getKey());
            int min = entry.getValue().get(0).asInt();
            int max = entry.getValue().get(1).asInt();
            boolean passed = !values.isEmpty() && values.stream()
                    .allMatch(node -> node.isNumber() && node.asInt() >= min && node.asInt() <= max);
            results.add(new Result("L1", "scoreRange:" + entry.getKey(), passed,
                    "值=" + values + ", 区间=[" + min + "," + max + "]"));
        });
    }

    private static void quotes(EvalCase evalCase, JsonNode actual, List<Result> results) {
        if (!evalCase.flag("quotesMustComeFromInput")) {
            return;
        }
        String input = normalizeQuote(evalCase.input());
        List<JsonNode> quotes = new ArrayList<>(collect(actual, "strengths[].quote"));
        quotes.addAll(collect(actual, "weaknesses[].quote"));
        String firstBad = quotes.stream()
                .map(node -> node.asText(""))
                .filter(text -> text.isBlank() || !input.contains(normalizeQuote(text)))
                .findFirst().orElse(null);
        results.add(new Result("L1", "quotesMustComeFromInput", firstBad == null,
                firstBad == null ? "引用数=" + quotes.size()
                        : "引用数=" + quotes.size() + ", 不匹配=" + abbreviate(firstBad)));
    }

    private static void maxItems(EvalCase evalCase, JsonNode actual, List<Result> results) {
        evalCase.expect().path("maxItems").fields().forEachRemaining(entry -> {
            int limit = entry.getValue().asInt();
            List<JsonNode> values = collect(actual, entry.getKey());
            int size = values.stream().mapToInt(node -> node.isArray() ? node.size() : 1).sum();
            results.add(new Result("L1", "maxItems:" + entry.getKey(), size <= limit, "size=" + size + ", max=" + limit));
        });
    }

    private static void overallFormula(EvalCase evalCase, JsonNode actual, List<Result> results) {
        if (!evalCase.expect().has("overallFormula")) {
            return;
        }
        Double overall = overall(actual);
        results.add(new Result("L1", "overallFormula", overall != null, "overall=" + overall));
    }

    private static void topics(EvalCase evalCase, JsonNode actual, List<Result> results) {
        List<String> required = evalCase.terms("topicsMustInclude");
        if (required.isEmpty()) {
            return;
        }
        Set<String> topics = new LinkedHashSet<>(extract(actual, "topics[].tag"));
        topics.addAll(extract(actual, "questions[].topics[]"));
        boolean passed = topics.containsAll(required);
        results.add(new Result("L2", "topicsMustInclude", passed, "实际=" + topics + ", 必要=" + required));
    }

    private static void lossTags(EvalCase evalCase, JsonNode actual, List<Result> results) {
        List<String> required = evalCase.terms("lossTagsMustInclude");
        if (required.isEmpty()) {
            return;
        }
        Set<String> tags = lossTags(actual);
        results.add(new Result("L2", "lossTagsMustInclude", tags.containsAll(required),
                "实际=" + tags + ", 必要=" + required));
    }

    private static void mention(EvalCase evalCase, JsonNode actual, List<Result> results) {
        List<String> terms = evalCase.terms("mustMentionAny");
        if (terms.isEmpty()) {
            return;
        }
        String text = normalize(String.join("\n", textsExceptRefs(actual)));
        boolean passed = terms.stream().map(Expectations::normalize).anyMatch(text::contains);
        results.add(new Result("L2", "mustMentionAny", passed, "关键词=" + terms));
    }

    private static void forbiddenTags(EvalCase evalCase, JsonNode actual, List<Result> results) {
        List<String> forbidden = evalCase.terms("mustNotContainTags");
        if (forbidden.isEmpty()) {
            return;
        }
        Set<String> tags = lossTags(actual);
        boolean passed = forbidden.stream().noneMatch(tags::contains);
        results.add(new Result("L2", "mustNotContainTags", passed, "实际=" + tags + ", 禁止=" + forbidden));
    }

    private static void skillsMin(EvalCase evalCase, JsonNode actual, List<Result> results) {
        if (!evalCase.expect().has("skillsMin")) {
            return;
        }
        int min = evalCase.expect().path("skillsMin").asInt();
        int size = collect(actual, "mustHaveSkills").stream().mapToInt(JsonNode::size).sum()
                + collect(actual, "niceToHaveSkills").stream().mapToInt(JsonNode::size).sum();
        results.add(new Result("L2", "skillsMin", size >= min, "size=" + size + ", min=" + min));
    }

    private static Set<String> lossTags(JsonNode actual) {
        Set<String> tags = new LinkedHashSet<>(extract(actual, "lossTags[].tag"));
        tags.addAll(extract(actual, "questions[].lossTags[]"));
        return tags;
    }

    private static Set<String> allTags(JsonNode actual) {
        Set<String> tags = new LinkedHashSet<>(extract(actual, "topics[].tag"));
        tags.addAll(extract(actual, "questions[].topics[]"));
        tags.addAll(lossTags(actual));
        return tags;
    }

    private static List<String> extract(JsonNode actual, String path) {
        return collect(actual, path).stream().map(JsonNode::asText).filter(text -> !text.isBlank()).toList();
    }

    private static List<String> textsExceptRefs(JsonNode node) {
        List<String> out = new ArrayList<>();
        collectTexts(node, out);
        return out;
    }

    private static void collectTexts(JsonNode node, List<String> out) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (!REF_FIELDS.contains(entry.getKey())) {
                    collectTexts(entry.getValue(), out);
                }
            });
        } else if (node.isArray()) {
            node.forEach(item -> collectTexts(item, out));
        } else if (node.isTextual()) {
            out.add(node.asText());
        }
    }

    private static List<String> scorePaths(String scene) {
        return switch (scene) {
            case "resume-analysis" -> RESUME_DIMENSIONS;
            case "review-analyze" -> List.of("questions[].score", "questions[].dimensions[].score");
            default -> List.of();
        };
    }

    private static String abbreviate(String text) {
        return text.length() <= 80 ? text : text.substring(0, 80) + "…";
    }

    /** 引用比对额外去掉标点，容忍省略号 / 逗号差异，但仍要求正文连续出现。 */
    private static String normalizeQuote(String text) {
        return text.replaceAll("[\\s\\p{P}]", "").toLowerCase(Locale.ROOT);
    }

    private static String normalize(String text) {
        return text.replaceAll("[\\s“”\"'‘’「」『』]", "").toLowerCase(Locale.ROOT);
    }
}
