package com.jobpilot.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobpilot.common.llm.ChatMessage;
import com.jobpilot.common.llm.ChatResult;
import com.jobpilot.common.llm.LlmClient;
import com.jobpilot.common.llm.OpenAiCompatibleClient;
import com.jobpilot.common.llm.StructuredOutputInvoker;
import com.jobpilot.modules.job.ai.AiJobPositionParse;
import com.jobpilot.modules.job.prompt.JobPositionPromptFactory;
import com.jobpilot.modules.resume.ai.AiResumeAnalysis;
import com.jobpilot.modules.resume.prompt.ResumePromptFactory;
import com.jobpilot.modules.review.ai.AiReviewAnalysis;
import com.jobpilot.modules.review.prompt.ReviewPromptFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 真实模型回归评测（默认 mvn test 排除，需显式 tag 与 EVAL_* 环境变量）。
 *
 * <p>运行示例：
 * <pre>mvn -o -f backend/pom.xml "-Djacoco.skip=true" "-DexcludedGroups=" "-Dgroups=llm-eval" test</pre>
 * 首次生成基线加 {@code -DEVAL_WRITE_BASELINE=true}。
 */
@Tag("llm-eval")
class LlmEvalTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final Path ROOT = Path.of("src/test/resources/llm-eval");
    private static final Path BASELINES = ROOT.resolve("baselines");
    private static final Path OUT = Path.of("target/llm-eval");

    private record Config(String baseUrl, String apiKey, String model, int maxTokens, int timeoutSeconds,
                          int repeat, boolean writeBaseline, Set<String> only) {
        static Config fromEnv() {
            return new Config(required("EVAL_BASE_URL"), required("EVAL_API_KEY"), required("EVAL_MODEL"),
                    integer("EVAL_MAX_TOKENS", 8192), integer("EVAL_TIMEOUT_SECONDS", 300),
                    integer("EVAL_REPEAT", 2),
                    Boolean.parseBoolean(System.getenv().getOrDefault("EVAL_WRITE_BASELINE", "false")),
                    Arrays.stream(System.getenv().getOrDefault("EVAL_ONLY", "").split(","))
                            .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet()));
        }

        private static String required(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("缺少环境变量 " + name
                        + "；需要 EVAL_BASE_URL / EVAL_API_KEY / EVAL_MODEL");
            }
            return value.trim();
        }

        private static int integer(String name, int fallback) {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        }
    }

    private record CaseRun(EvalCase evalCase, String status, List<Expectations.Result> results,
                           JsonNode actual, Integer tokens, long elapsedMs, String error) {
        boolean passed() {
            return "PASS".equals(status) || "GRACEFUL".equals(status);
        }
    }

    @Test
    void runRegression() throws Exception {
        Config config = Config.fromEnv();
        List<EvalCase> all = EvalCorpus.load(ROOT);
        List<EvalCase> cases = config.only().isEmpty() ? all
                : all.stream().filter(c -> config.only().contains(c.id())
                        || config.only().contains(c.scene() + "/" + c.id())).toList();
        if (cases.isEmpty()) {
            throw new IllegalStateException("EVAL_ONLY 未匹配任何 case");
        }

        LlmClient client = new OpenAiCompatibleClient(config.baseUrl(), config.apiKey(), config.maxTokens(),
                java.time.Duration.ofSeconds(config.timeoutSeconds()));
        StructuredOutputInvoker invoker = new StructuredOutputInvoker(client);
        List<CaseRun> runs = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        List<String> l2Misses = new ArrayList<>();
        int l2Total = 0;
        int l2Failed = 0;
        for (EvalCase evalCase : cases) {
            CaseRun run = runCase(config, invoker, evalCase);
            runs.add(run);
            System.out.println("[" + runs.size() + "/" + cases.size() + "] " + evalCase.scene() + "/"
                    + evalCase.id() + " -> " + run.status()
                    + (run.tokens() == null ? "" : " tokens=" + run.tokens())
                    + (run.error() == null ? "" : " | " + run.error().replaceAll("\\s+", " ")));
            for (Expectations.Result result : run.results()) {
                if ("L2".equals(result.layer())) {
                    l2Total++;
                    if (!result.passed()) {
                        l2Failed++;
                    }
                }
            }
            if (!run.passed()) {
                String hard = hardFailure(run);
                if (hard != null) {
                    failures.add(evalCase.scene() + "/" + evalCase.id() + " -> " + run.status() + " " + hard);
                } else {
                    l2Misses.add(evalCase.scene() + "/" + evalCase.id());
                }
            }
        }
        if (l2Total > 0 && (l2Total - l2Failed) * 100 < l2Total * 95) {
            failures.add("L2 通过率 " + (l2Total - l2Failed) + "/" + l2Total + " 低于 95%：" + l2Misses);
        } else if (!l2Misses.isEmpty()) {
            System.out.println("L2 未通过用例（通过率阈值内，不判失败）：" + l2Misses);
        }

        Reports.write(runs, config);
        if (config.writeBaseline()) {
            if (!config.only().isEmpty()) {
                failures.add("生成基线时必须跑全量，不能使用 EVAL_ONLY");
            } else {
                Baseline.write(runs, config.model());
                System.out.println("基线已写入 " + BASELINES + "，失败用例 " + failures.size() + " 个（详见报告）");
                return;
            }
        } else if (config.only().isEmpty()) {
            Baseline.compare(runs, config, failures);
        }
        assertThat(failures).isEmpty();
    }

    private CaseRun runCase(Config config, StructuredOutputInvoker invoker, EvalCase evalCase) {
        long start = System.nanoTime();
        try {
            Run first = invoke(config, invoker, evalCase);
            JsonNode second = null;
            if (config.repeat() > 1) {
                second = invoke(config, invoker, evalCase).json();
            }
            List<Expectations.Result> results = new ArrayList<>(Expectations.evaluate(evalCase, first.json()));
            if (second != null) {
                results.addAll(Expectations.stability(evalCase, first.json(), second));
            }
            boolean passed = results.stream().allMatch(Expectations.Result::passed);
            return new CaseRun(evalCase, passed ? "PASS" : "FAIL", results, first.json(), first.tokens(),
                    elapsed(start), passed ? null : failedNames(results));
        } catch (Exception ex) {
            if (evalCase.flag("expectGraceful")) {
                return new CaseRun(evalCase, "GRACEFUL", List.of(), null, null, elapsed(start), ex.getMessage());
            }
            return new CaseRun(evalCase, "FAIL", List.of(), null, null, elapsed(start), ex.getMessage());
        }
    }

    private Run invoke(Config config, StructuredOutputInvoker invoker, EvalCase evalCase) throws Exception {
        long start = System.nanoTime();
        StructuredOutputInvoker.Parsed<?> parsed = invoker.invokeWithUsage(config.model(),
                messages(evalCase), dtoType(evalCase.scene()));
        ChatResult usage = parsed.result();
        return new Run(M.valueToTree(parsed.value()), usage == null ? null : usage.totalTokens(),
                elapsed(start));
    }

    private record Run(JsonNode json, Integer tokens, long elapsedMs) {
    }

    private static List<ChatMessage> messages(EvalCase evalCase) {
        return switch (evalCase.scene()) {
            case "resume-analysis" -> {
                ResumePromptFactory prompts = new ResumePromptFactory();
                yield List.of(ChatMessage.system(prompts.system()),
                        ChatMessage.user(prompts.user(evalCase.input(), null)));
            }
            case "review-analyze" -> {
                ReviewPromptFactory prompts = new ReviewPromptFactory();
                yield List.of(ChatMessage.system(prompts.system()),
                        ChatMessage.user(prompts.user(evalCase.text("companyName"), evalCase.text("jobTitle"),
                                evalCase.text("roundType"), evalCase.text("interviewerRole"),
                                evalCase.optionalFile("company-intro.txt"), evalCase.optionalFile("jd.txt"),
                                evalCase.optionalFile("resume.txt"), evalCase.input())));
            }
            default -> {
                JobPositionPromptFactory prompts = new JobPositionPromptFactory();
                yield List.of(ChatMessage.system(prompts.system()),
                        ChatMessage.user(prompts.user(evalCase.input())));
            }
        };
    }

    private static Class<?> dtoType(String scene) {
        return switch (scene) {
            case "resume-analysis" -> AiResumeAnalysis.class;
            case "review-analyze" -> AiReviewAnalysis.class;
            default -> AiJobPositionParse.class;
        };
    }

    /** L1/L3 硬失败；仅 L2 未通过返回 null，交由整轮 95% 通过率判定。 */
    private static String hardFailure(CaseRun run) {
        if (run.actual() == null) {
            return run.error();
        }
        List<Expectations.Result> hard = run.results().stream()
                .filter(result -> !"L2".equals(result.layer()) && !result.passed()).toList();
        return hard.isEmpty() ? null : failedNames(hard);
    }

    private static String failedNames(List<Expectations.Result> results) {
        return results.stream().filter(r -> !r.passed())
                .map(r -> r.layer() + ":" + r.name() + "(" + r.detail() + ")")
                .collect(Collectors.joining("; "));
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** 报告与基线读写；只写 target 与 baselines，不触碰业务代码。 */
    static final class Reports {

        private Reports() {
        }

        static void write(List<CaseRun> runs, Config config) throws Exception {
            Files.createDirectories(OUT);
            ObjectNode json = M.createObjectNode();
            json.put("generatedAt", OffsetDateTime.now().toString());
            json.put("model", config.model());
            json.put("maxTokens", config.maxTokens());
            json.put("timeoutSeconds", config.timeoutSeconds());
            json.put("repeat", config.repeat());
            ArrayNode cases = json.putArray("cases");
            for (CaseRun run : runs) {
                ObjectNode node = cases.addObject();
                node.put("scene", run.evalCase().scene());
                node.put("case", run.evalCase().id());
                node.put("status", run.status());
                node.put("tokens", run.tokens());
                node.put("elapsedMs", run.elapsedMs());
                node.put("error", run.error());
                ArrayNode results = node.putArray("results");
                for (Expectations.Result result : run.results()) {
                    ObjectNode item = results.addObject();
                    item.put("layer", result.layer());
                    item.put("name", result.name());
                    item.put("passed", result.passed());
                    item.put("detail", result.detail());
                }
            }
            Files.writeString(OUT.resolve("report.json"), M.writerWithDefaultPrettyPrinter().writeValueAsString(json),
                    StandardCharsets.UTF_8);

            StringBuilder markdown = new StringBuilder("# LLM 回归评测报告\n\n")
                    .append("- 时间：").append(json.path("generatedAt").asText()).append('\n')
                    .append("- 模型：").append(config.model()).append('\n')
                    .append("- maxTokens：").append(config.maxTokens())
                    .append(" / 超时：").append(config.timeoutSeconds()).append("s\n")
                    .append("- 重复：").append(config.repeat()).append("\n\n")
                    .append("| 场景 | case | 状态 | L1 | L2 | L3 | tokens | ms | 失败项 |\n")
                    .append("|---|---|---|---|---|---|---|---|---|\n");
            for (CaseRun run : runs) {
                markdown.append("| ").append(run.evalCase().scene())
                        .append(" | ").append(run.evalCase().id())
                        .append(" | ").append(run.status())
                        .append(" | ").append(layer(run, "L1"))
                        .append(" | ").append(layer(run, "L2"))
                        .append(" | ").append(layer(run, "L3"))
                        .append(" | ").append(run.tokens() == null ? "-" : run.tokens())
                        .append(" | ").append(run.elapsedMs())
                        .append(" | ").append(run.error() == null ? ""
                                : run.error().replace("|", "/").replaceAll("\\s+", " "))
                        .append(" |\n");
            }
            Files.writeString(OUT.resolve("report.md"), markdown.toString(), StandardCharsets.UTF_8);
        }

        private static String layer(CaseRun run, String layer) {
            List<Expectations.Result> results = run.results().stream()
                    .filter(r -> layer.equals(r.layer())).toList();
            if (results.isEmpty()) {
                return "-";
            }
            return results.stream().allMatch(Expectations.Result::passed) ? "PASS" : "FAIL";
        }
    }

    /** 每场景一个基线文件；case id 集合必须一致，分数漂移与标签相似度按规范判定。 */
    static final class Baseline {

        private Baseline() {
        }

        static void write(List<CaseRun> runs, String model) throws Exception {
            Files.createDirectories(BASELINES);
            Map<String, List<CaseRun>> byScene = runs.stream()
                    .collect(Collectors.groupingBy(r -> r.evalCase().scene()));
            for (Map.Entry<String, List<CaseRun>> entry : byScene.entrySet()) {
                ObjectNode root = M.createObjectNode();
                root.put("generatedAt", OffsetDateTime.now().toString());
                root.put("model", model);
                ObjectNode cases = root.putObject("cases");
                entry.getValue().forEach(run -> cases.set(run.evalCase().id(), snapshot(run)));
                Files.writeString(BASELINES.resolve(entry.getKey() + ".json"),
                        M.writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
            }
        }

        static void compare(List<CaseRun> runs, Config config, List<String> failures) throws Exception {
            Map<String, List<CaseRun>> byScene = runs.stream()
                    .collect(Collectors.groupingBy(r -> r.evalCase().scene()));
            for (Map.Entry<String, List<CaseRun>> entry : byScene.entrySet()) {
                Path file = BASELINES.resolve(entry.getKey() + ".json");
                if (!Files.isRegularFile(file)) {
                    failures.add("缺少基线 " + file + "；首次运行请加 -DEVAL_WRITE_BASELINE=true");
                    continue;
                }
                JsonNode baseline = M.readTree(Files.readString(file, StandardCharsets.UTF_8));
                JsonNode cases = baseline.path("cases");
                Set<String> expected = new LinkedHashSet<>();
                cases.fieldNames().forEachRemaining(expected::add);
                Set<String> actual = entry.getValue().stream().map(r -> r.evalCase().id())
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                if (!expected.equals(actual)) {
                    failures.add(entry.getKey() + " 基线 case 集合不一致：基线=" + expected + ", 实际=" + actual);
                    continue;
                }
                if (!config.model().equals(baseline.path("model").asText())) {
                    failures.add(entry.getKey() + " 基线模型不一致：基线=" + baseline.path("model").asText()
                            + ", 当前=" + config.model() + "；换模型请重建基线");
                }
                for (CaseRun run : entry.getValue()) {
                    compareCase(run, cases.path(run.evalCase().id()), failures);
                }
            }
        }

        private static void compareCase(CaseRun run, JsonNode base, List<String> failures) {
            if (base.isMissingNode()) {
                return;
            }
            String label = run.evalCase().scene() + "/" + run.evalCase().id();
            if (run.actual() == null) {
                if (!run.evalCase().flag("expectGraceful")) {
                    failures.add(label + " 本次未获得结果，基线存在但无法对比");
                }
                return;
            }
            JsonNode scores = base.path("scores");
            scores.fields().forEachRemaining(entry -> {
                Double current = score(run.actual(), entry.getKey());
                if (current != null && Math.abs(current - entry.getValue().asDouble()) > 10) {
                    failures.add(label + " 分数漂移 >10：" + entry.getKey()
                            + " 基线=" + entry.getValue().asDouble() + ", 当前=" + current);
                }
            });
            Set<String> baseTags = tags(base);
            Set<String> nowTags = tags(snapshot(run));
            Set<String> union = new LinkedHashSet<>(baseTags);
            union.addAll(nowTags);
            Set<String> intersection = new LinkedHashSet<>(baseTags);
            intersection.retainAll(nowTags);
            double jaccard = union.isEmpty() ? 1.0 : (double) intersection.size() / union.size();
            if (jaccard < 0.7) {
                failures.add(label + " 标签 Jaccard=" + String.format(java.util.Locale.ROOT, "%.2f", jaccard) + " < 0.7");
            }
        }

        private static ObjectNode snapshot(CaseRun run) {
            ObjectNode node = M.createObjectNode();
            node.put("status", run.status());
            ObjectNode scores = node.putObject("scores");
            if (run.actual() != null) {
                for (String path : scorePaths(run.evalCase().scene())) {
                    List<JsonNode> values = Expectations.collect(run.actual(), path);
                    for (int i = 0; i < values.size(); i++) {
                        if (values.get(i).isNumber()) {
                            scores.put(path + "#" + i, values.get(i).asDouble());
                        }
                    }
                }
            }
            Double overall = run.actual() == null ? null : Expectations.overall(run.actual());
            if (overall != null) {
                scores.put("overall#0", overall);
            }
            node.set("topics", M.valueToTree(topicTags(run.actual())));
            node.set("lossTags", M.valueToTree(lossTags(run.actual())));
            node.put("tokens", run.tokens());
            node.put("elapsedMs", run.elapsedMs());
            return node;
        }

        private static Set<String> tags(JsonNode node) {
            Set<String> tags = new LinkedHashSet<>();
            node.path("topics").forEach(item -> tags.add(item.asText()));
            node.path("lossTags").forEach(item -> tags.add(item.asText()));
            return tags;
        }

        private static Set<String> topicTags(JsonNode actual) {
            Set<String> tags = new LinkedHashSet<>();
            if (actual == null) {
                return tags;
            }
            Expectations.collect(actual, "topics[].tag").forEach(n -> tags.add(n.asText()));
            Expectations.collect(actual, "questions[].topics[]").forEach(n -> tags.add(n.asText()));
            return tags;
        }

        private static Set<String> lossTags(JsonNode actual) {
            Set<String> tags = new LinkedHashSet<>();
            if (actual == null) {
                return tags;
            }
            Expectations.collect(actual, "lossTags[].tag").forEach(n -> tags.add(n.asText()));
            Expectations.collect(actual, "questions[].lossTags[]").forEach(n -> tags.add(n.asText()));
            return tags;
        }

        private static Double score(JsonNode actual, String key) {
            int index = key.lastIndexOf('#');
            if (index < 0) {
                return null;
            }
            String path = key.substring(0, index);
            int position = Integer.parseInt(key.substring(index + 1));
            List<JsonNode> values = Expectations.collect(actual, path);
            return position < values.size() && values.get(position).isNumber()
                    ? values.get(position).asDouble() : null;
        }

        private static List<String> scorePaths(String scene) {
            return switch (scene) {
                case "resume-analysis" -> List.of("technicalDepth", "projectQuality", "engineeringAbility",
                        "expressionStructure", "jobMatchPotential");
                case "review-analyze" -> List.of("questions[].score", "questions[].dimensions[].score");
                default -> List.of();
            };
        }
    }
}
