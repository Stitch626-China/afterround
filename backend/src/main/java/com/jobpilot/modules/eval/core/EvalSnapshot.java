package com.jobpilot.modules.eval.core;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/** 用例结果快照：分数、话题与失分标签，用于基线对比与回归分类。 */
public record EvalSnapshot(Map<String, Double> scores, Set<String> topics, Set<String> lossTags) {

    private static final List<String> RESUME_DIMENSIONS = List.of("technicalDepth", "projectQuality",
            "engineeringAbility", "expressionStructure", "jobMatchPotential");

    public static EvalSnapshot of(String scene, JsonNode actual) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Set<String> topics = new LinkedHashSet<>();
        Set<String> lossTags = new LinkedHashSet<>();
        if (actual != null && !actual.isNull()) {
            switch (scene) {
                case "resume-analysis" -> {
                    for (String field : RESUME_DIMENSIONS) {
                        if (actual.path(field).isNumber()) {
                            scores.put(field + "#0", actual.path(field).asDouble());
                        }
                    }
                    Double overall = Expectations.overall(actual);
                    if (overall != null) {
                        scores.put("overall#0", overall);
                    }
                }
                case "review-analyze" -> scores.putAll(Expectations.reviewScores(actual));
                default -> {
                }
            }
            Expectations.collect(actual, "topics[].tag").forEach(node -> topics.add(node.asText()));
            Expectations.collect(actual, "questions[].topics[]").forEach(node -> topics.add(node.asText()));
            Expectations.collect(actual, "lossTags[].tag").forEach(node -> lossTags.add(node.asText()));
            Expectations.collect(actual, "questions[].lossTags[]").forEach(node -> lossTags.add(node.asText()));
        }
        return new EvalSnapshot(scores, topics, lossTags);
    }
}
