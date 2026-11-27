package com.jobpilot.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** 断言引擎单元测试：路径解析、L1/L2 断言与 L3 稳定性。 */
class ExpectationsTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static EvalCase evalCase(String scene, String expectJson) throws Exception {
        return new EvalCase(scene, "001-x", Path.of("."), "负责接口开发，优化了接口性能。",
                M.readTree("{\"scene\":\"" + scene + "\"}"), M.readTree(expectJson));
    }

    private static JsonNode json(String value) throws Exception {
        return M.readTree(value);
    }

    private static boolean failed(List<Expectations.Result> results, String prefix) {
        return results.stream().anyMatch(r -> !r.passed() && r.name().startsWith(prefix));
    }

    @Test
    void collectsWildcardValues() throws Exception {
        JsonNode root = json("{\"questions\":[{\"score\":50},{\"score\":70}]}");
        assertThat(Expectations.collect(root, "questions[].score"))
                .extracting(JsonNode::asInt).containsExactly(50, 70);
    }

    @Test
    void reportsMissingRequiredField() throws Exception {
        var results = Expectations.evaluate(evalCase("resume-analysis",
                "{\"requiredFields\":[\"technicalDepth\"]}"), json("{\"strengths\":[]}"));
        assertThat(failed(results, "requiredFields")).isTrue();
    }

    @Test
    void reportsOutOfRangeScore() throws Exception {
        var results = Expectations.evaluate(evalCase("resume-analysis",
                "{\"scoreRange\":{\"technicalDepth\":[0,70]}}"), json("{\"technicalDepth\":88}"));
        assertThat(failed(results, "scoreRange")).isTrue();
    }

    @Test
    void rejectsFabricatedQuoteAndAcceptsContainedQuote() throws Exception {
        var caseFile = evalCase("resume-analysis", "{\"quotesMustComeFromInput\":true}");
        var bad = Expectations.evaluate(caseFile,
                json("{\"strengths\":[{\"quote\":\"主导了千万级交易系统\"}]}"));
        var good = Expectations.evaluate(caseFile,
                json("{\"strengths\":[{\"quote\":\"优化了接口性能\"}]}"));
        assertThat(failed(bad, "quotesMustComeFromInput")).isTrue();
        assertThat(failed(good, "quotesMustComeFromInput")).isFalse();
    }

    @Test
    void quoteComparisonIgnoresPunctuationDifferences() throws Exception {
        var caseFile = evalCase("resume-analysis", "{\"quotesMustComeFromInput\":true}");
        var results = Expectations.evaluate(caseFile,
                json("{\"strengths\":[{\"quote\":\"优化了接口性能。\"}]}"));
        assertThat(failed(results, "quotesMustComeFromInput")).isFalse();
    }

    @Test
    void mustMentionIgnoresEvidenceAndQuote() throws Exception {
        var caseFile = evalCase("review-analyze", "{\"mustMentionAny\":[\"AQS\"]}");
        var results = Expectations.evaluate(caseFile,
                json("{\"summary\":{\"overall\":\"未提及并发\"},\"questions\":[{\"evidence\":\"AQS\"}]}"));
        assertThat(failed(results, "mustMentionAny")).isTrue();
    }

    @Test
    void topicsUnionCoversQuestionTopics() throws Exception {
        var caseFile = evalCase("review-analyze", "{\"topicsMustInclude\":[\"jvm\"]}");
        var results = Expectations.evaluate(caseFile,
                json("{\"topics\":[],\"questions\":[{\"topics\":[\"jvm\"]}]}"));
        assertThat(failed(results, "topicsMustInclude")).isFalse();
    }

    @Test
    void forbiddenLossTagFails() throws Exception {
        var caseFile = evalCase("review-analyze",
                "{\"mustNotContainTags\":[\"knowledge-gap\"]}");
        var results = Expectations.evaluate(caseFile,
                json("{\"topics\":[],\"lossTags\":[{\"tag\":\"knowledge-gap\"}],\"questions\":[]}"));
        assertThat(failed(results, "mustNotContainTags")).isTrue();
    }

    @Test
    void maxItemsRejectsTooManyActions() throws Exception {
        var caseFile = evalCase("review-analyze", "{\"maxItems\":{\"actions\":3}}");
        var results = Expectations.evaluate(caseFile,
                json("{\"actions\":[{},{},{},{}]}"));
        assertThat(failed(results, "maxItems")).isTrue();
    }

    @Test
    void overallFormulaRequiresAllDimensions() throws Exception {
        var caseFile = evalCase("resume-analysis", "{\"overallFormula\":\"30/25/20/15/10\"}");
        var ok = Expectations.evaluate(caseFile,
                json("{\"technicalDepth\":80,\"projectQuality\":80,\"engineeringAbility\":80,"
                        + "\"expressionStructure\":80,\"jobMatchPotential\":80}"));
        var missing = Expectations.evaluate(caseFile, json("{\"technicalDepth\":80}"));
        assertThat(failed(ok, "overallFormula")).isFalse();
        assertThat(failed(missing, "overallFormula")).isTrue();
    }

    @Test
    void stabilityDetectsScoreDrift() throws Exception {
        var caseFile = evalCase("review-analyze", "{\"scoreDriftMax\":5}");
        var first = json("{\"questions\":[{\"score\":50}]}");
        var second = json("{\"questions\":[{\"score\":62}]}");
        assertThat(Expectations.stability(caseFile, first, second).stream()
                .anyMatch(r -> !r.passed() && r.name().startsWith("scoreDriftMax"))).isTrue();
    }

    @Test
    void reviewStabilityAlignsByQuestionIndexNotArrayOrder() throws Exception {
        var caseFile = evalCase("review-analyze", "{\"scoreDriftMax\":5}");
        var first = json("{\"questions\":[{\"questionIndex\":1,\"score\":50},"
                + "{\"questionIndex\":2,\"score\":70}]}");
        var second = json("{\"questions\":[{\"questionIndex\":2,\"score\":72},"
                + "{\"questionIndex\":1,\"score\":52}]}");
        assertThat(Expectations.stability(caseFile, first, second).stream()
                .filter(r -> r.name().startsWith("scoreDriftMax"))
                .allMatch(Expectations.Result::passed)).isTrue();
    }

    @Test
    void stabilityDetectsTagJaccardDrop() throws Exception {
        var caseFile = evalCase("review-analyze", "{\"tagJaccardMin\":0.7}");
        var first = json("{\"topics\":[{\"tag\":\"jvm\"},{\"tag\":\"redis\"}]}");
        var second = json("{\"topics\":[{\"tag\":\"jvm\"},{\"tag\":\"mysql\"}]}");
        assertThat(Expectations.stability(caseFile, first, second).stream()
                .anyMatch(r -> !r.passed() && r.name().startsWith("tagJaccardMin"))).isTrue();
    }

    @Test
    void skillsMinCountsBothSkillLists() throws Exception {
        var caseFile = evalCase("jd-parse", "{\"skillsMin\":3}");
        var results = Expectations.evaluate(caseFile,
                json("{\"mustHaveSkills\":[\"Java\"],\"niceToHaveSkills\":[\"Redis\"]}"));
        assertThat(failed(results, "skillsMin")).isTrue();
    }
}
