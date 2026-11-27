package com.jobpilot.modules.eval.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * 语料扫描与合规校验（规范见 docs/LLM评测集语料规范.md）。
 *
 * <p>运行时从 classpath 加载 27 份语料；{@link #validate(Path)} 保留给本地临时语料与语料编辑自检。
 */
public final class EvalCorpus {

    private static final ObjectMapper M = new ObjectMapper();
    private static final List<String> SCENES = List.of("resume-analysis", "review-analyze", "jd-parse");
    private static final String CLASSPATH_PATTERN = "classpath*:llm-eval/cases/*/*/meta.json";
    private static final String CLASSPATH_PREFIX = "llm-eval/cases/";
    private static final List<String> OPTIONAL_FILES = List.of("jd.txt", "company-intro.txt", "resume.txt");
    private static final Set<String> COMMON_META = Set.of("scene", "sourceType", "sanitized", "note", "longInput");
    private static final Set<String> REVIEW_META = Set.of("companyName", "jobTitle", "roundType",
            "interviewerRole", "format", "inputType");
    private static final Set<String> EXPECT_KEYS = Set.of("requiredFields", "scoreRange", "quotesMustComeFromInput",
            "maxItems", "overallFormula", "topicsMustInclude", "lossTagsMustInclude", "mustMentionAny",
            "mustNotContainTags", "skillsMin", "expectGraceful", "scoreDriftMax", "tagJaccardMin");
    private static final Set<String> SOURCE_TYPES = Set.of("real-transcript", "real-notes", "community-rewrite",
            "synthetic", "negative");
    private static final Set<String> ROUND_TYPES = Set.of("HR", "TECH", "CROSS", "DIRECTOR", "FINAL", "OTHER");
    private static final Set<String> INPUT_TYPES = Set.of("TEXT", "NOTES", "AUDIO");
    private static final Set<String> FORMATS = Set.of("PHONE", "VIDEO", "ONSITE", "OTHER");
    private static final Set<String> TOPICS = Set.of("java", "jvm", "concurrency", "mysql", "redis", "kafka",
            "spring", "distributed", "system-design", "algorithm", "project", "communication", "career", "other");
    private static final Set<String> LOSS_TAGS = Set.of("knowledge-gap", "shallow-depth", "unclear-expression",
            "no-evidence", "off-topic", "time-management", "pressure", "other");
    private static final Set<String> CASE_FILES = Set.of("input.txt", "meta.json", "expect.json",
            "jd.txt", "company-intro.txt", "resume.txt");
    private static final List<Pattern> PRIVACY = List.of(
            Pattern.compile("\\b1[3-9]\\d{9}\\b"),
            Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+"),
            Pattern.compile("\\b\\d{17}[\\dXx]\\b"));

    private static final Map<String, Fields> FIELDS = Map.of(
            "resume-analysis", new Fields(
                    List.of("technicalDepth", "projectQuality", "engineeringAbility", "expressionStructure",
                            "jobMatchPotential", "basicInfo", "strengths", "weaknesses", "suggestions",
                            "careerDirections"),
                    List.of("technicalDepth", "projectQuality", "engineeringAbility", "expressionStructure",
                            "jobMatchPotential"),
                    List.of("strengths", "weaknesses", "suggestions", "careerDirections")),
            "review-analyze", new Fields(
                    List.of("questions", "topics", "lossTags", "summary", "actions"),
                    List.of("questions[].score", "questions[].dimensions[].score"),
                    List.of("questions", "topics", "lossTags", "actions")),
            "jd-parse", new Fields(
                    List.of("title", "companyName", "city", "salaryMin", "salaryMax", "experience", "education",
                            "mustHaveSkills", "niceToHaveSkills", "responsibility"),
                    List.of("salaryMin", "salaryMax"),
                    List.of("mustHaveSkills", "niceToHaveSkills")));

    private record Fields(List<String> top, List<String> numeric, List<String> lists) {
    }

    private EvalCorpus() {
    }

    /** 从 classpath 加载全部语料；不合规时抛出包含全部问题的异常。 */
    public static List<EvalCase> load(ResourcePatternResolver resolver) {
        List<String> issues = new ArrayList<>();
        List<EvalCase> cases = scan(resolver, issues);
        if (!issues.isEmpty()) {
            throw new IllegalStateException("语料不合规:\n - " + String.join("\n - ", issues));
        }
        return cases;
    }

    /** 只校验不加载，供默认测试与人工抽检使用。 */
    public static List<String> validate(ResourcePatternResolver resolver) {
        List<String> issues = new ArrayList<>();
        scan(resolver, issues);
        return issues;
    }

    /** 目录加载，保留给本地语料编辑与单测。 */
    public static List<EvalCase> load(Path root) {
        List<String> issues = new ArrayList<>();
        List<EvalCase> cases = scan(root, issues);
        if (!issues.isEmpty()) {
            throw new IllegalStateException("语料不合规:\n - " + String.join("\n - ", issues));
        }
        return cases;
    }

    public static List<String> validate(Path root) {
        List<String> issues = new ArrayList<>();
        scan(root, issues);
        return issues;
    }

    private static List<EvalCase> scan(ResourcePatternResolver resolver, List<String> issues) {
        List<EvalCase> cases = new ArrayList<>();
        Map<String, List<String>> ids = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        try {
            for (Resource meta : resolver.getResources(CLASSPATH_PATTERN)) {
                String url = meta.getURL().toString();
                int index = url.indexOf(CLASSPATH_PREFIX);
                String[] parts = index < 0 ? new String[0]
                        : url.substring(index + CLASSPATH_PREFIX.length()).split("/");
                if (parts.length != 3 || !"meta.json".equals(parts[2])) {
                    issues.add(url + ": 语料路径应为 cases/场景/case/meta.json");
                    continue;
                }
                String scene = parts[0];
                String id = parts[1];
                String label = scene + "/" + id;
                if (!seen.add(label)) {
                    continue;
                }
                ids.computeIfAbsent(scene, key -> new ArrayList<>()).add(id);
                String base = url.substring(0, url.length() - "meta.json".length());
                Resource input = resolver.getResource(base + "input.txt");
                if (!input.exists()) {
                    issues.add(label + ": 缺少 input.txt");
                }
                Resource expect = resolver.getResource(base + "expect.json");
                if (!expect.exists()) {
                    issues.add(label + ": 缺少 expect.json");
                }
                Map<String, String> files = new LinkedHashMap<>();
                for (String name : OPTIONAL_FILES) {
                    Resource file = resolver.getResource(base + name);
                    if (file.exists()) {
                        files.put(name, readText(label + "/" + name, file, issues, false));
                    }
                }
                collect(scene, id, input.exists() ? readText(label + "/input.txt", input, issues, true) : null,
                        readJson(label + "/meta.json", meta, issues),
                        expect.exists() ? readJson(label + "/expect.json", expect, issues) : null,
                        files, cases, issues);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("扫描 classpath 语料失败", ex);
        }
        ids.values().forEach(java.util.Collections::sort);
        ids.forEach((scene, list) -> {
            for (int i = 0; i < list.size(); i++) {
                checkName(scene + "/" + list.get(i), list.get(i), i + 1, issues);
            }
        });
        cases.sort(Comparator.comparingInt((EvalCase item) -> SCENES.indexOf(item.scene()))
                .thenComparing(EvalCase::id));
        return cases;
    }

    private static List<EvalCase> scan(Path root, List<String> issues) {
        Path casesDir = Files.isDirectory(root.resolve("cases")) ? root.resolve("cases") : root;
        List<EvalCase> cases = new ArrayList<>();
        for (String scene : SCENES) {
            Path sceneDir = casesDir.resolve(scene);
            if (!Files.isDirectory(sceneDir)) {
                continue;
            }
            List<Path> dirs = childDirs(sceneDir);
            for (int i = 0; i < dirs.size(); i++) {
                Path dir = dirs.get(i);
                String id = dir.getFileName().toString();
                String label = scene + "/" + id;
                checkName(label, id, i + 1, issues);
                checkFiles(label, dir, issues);
                Map<String, String> files = new LinkedHashMap<>();
                for (String name : OPTIONAL_FILES) {
                    Path file = dir.resolve(name);
                    if (Files.isRegularFile(file)) {
                        files.put(name, readText(label + "/" + name, file, issues, false));
                    }
                }
                collect(scene, id, readInput(label, dir, issues),
                        readJson(label + "/meta.json", dir.resolve("meta.json"), issues),
                        readJson(label + "/expect.json", dir.resolve("expect.json"), issues),
                        files, cases, issues);
            }
        }
        return cases;
    }

    private static void collect(String scene, String id, String input, JsonNode meta, JsonNode expect,
                                Map<String, String> files, List<EvalCase> cases, List<String> issues) {
        String label = scene + "/" + id;
        if (meta != null) {
            checkMeta(label, scene, meta, input, issues);
        }
        if (expect != null) {
            checkExpect(label, scene, expect, issues);
        }
        if (input != null && meta != null && expect != null) {
            cases.add(new EvalCase(scene, id, input, meta, expect, files));
        }
    }

    private static List<Path> childDirs(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isDirectory).sorted().toList();
        } catch (IOException ex) {
            throw new IllegalStateException("扫描语料目录失败: " + dir, ex);
        }
    }

    private static void checkName(String label, String id, int expectedNo, List<String> issues) {
        if (!id.matches("\\d{3}-[a-z0-9]+(-[a-z0-9]+)*")) {
            issues.add(label + ": 目录名需为「三位序号-小写语义标签」");
        }
        String expected = String.format("%03d-", expectedNo);
        if (!id.startsWith(expected)) {
            issues.add(label + ": 序号不连续，期望 " + expected);
        }
    }

    private static void checkFiles(String label, Path dir, List<String> issues) {
        for (String required : List.of("input.txt", "meta.json", "expect.json")) {
            if (!Files.isRegularFile(dir.resolve(required))) {
                issues.add(label + ": 缺少 " + required);
            }
        }
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> !CASE_FILES.contains(name))
                    .forEach(name -> issues.add(label + ": 未知文件 " + name));
        } catch (IOException ex) {
            issues.add(label + ": 无法读取目录 " + ex.getMessage());
        }
    }

    private static String readInput(String label, Path dir, List<String> issues) {
        return readText(label + "/input.txt", dir.resolve("input.txt"), issues, true);
    }

    private static String readText(String label, Resource resource, List<String> issues, boolean input) {
        try (InputStream in = resource.getInputStream()) {
            return text(label, in.readAllBytes(), issues, input);
        } catch (IOException ex) {
            issues.add(label + ": 读取失败 " + ex.getMessage());
            return null;
        }
    }

    private static String readText(String label, Path file, List<String> issues, boolean input) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return text(label, Files.readAllBytes(file), issues, input);
        } catch (IOException ex) {
            issues.add(label + ": 读取失败 " + ex.getMessage());
            return null;
        }
    }

    private static String text(String label, byte[] bytes, List<String> issues, boolean input) {
        if (input && bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            issues.add(label + ": 含 BOM");
        }
        String text = new String(bytes, StandardCharsets.UTF_8).replace("\uFEFF", "");
        if (!input) {
            return text;
        }
        if (text.isBlank()) {
            issues.add(label + ": 为空");
        }
        for (Pattern pattern : PRIVACY) {
            if (pattern.matcher(text).find()) {
                issues.add(label + ": 命中隐私正则 " + pattern.pattern());
            }
        }
        return text;
    }

    private static JsonNode readJson(String label, Resource file, List<String> issues) {
        try (InputStream in = file.getInputStream()) {
            return M.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception ex) {
            issues.add(label + ": JSON 解析失败 " + ex.getMessage());
            return null;
        }
    }

    private static JsonNode readJson(String label, Path file, List<String> issues) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return M.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (Exception ex) {
            issues.add(label + ": JSON 解析失败 " + ex.getMessage());
            return null;
        }
    }

    private static void checkMeta(String label, String scene, JsonNode meta, String input, List<String> issues) {
        Set<String> allowed = new LinkedHashSet<>(COMMON_META);
        if ("review-analyze".equals(scene)) {
            allowed.addAll(REVIEW_META);
        }
        meta.fieldNames().forEachRemaining(key -> {
            if (!allowed.contains(key)) {
                issues.add(label + "/meta.json: 未知键 " + key);
            }
        });
        if (!scene.equals(meta.path("scene").asText())) {
            issues.add(label + "/meta.json: scene=" + meta.path("scene").asText() + " 与目录不符");
        }
        if (!SOURCE_TYPES.contains(meta.path("sourceType").asText())) {
            issues.add(label + "/meta.json: sourceType 非法 " + meta.path("sourceType").asText());
        }
        if (!meta.path("sanitized").asBoolean(false)) {
            issues.add(label + "/meta.json: sanitized 必须为 true");
        }
        if (input != null && input.length() > 30000 && !meta.path("longInput").asBoolean(false)) {
            issues.add(label + "/meta.json: 输入超过 30000 字符需标记 longInput=true");
        }
        if ("review-analyze".equals(scene)) {
            for (String field : List.of("companyName", "jobTitle", "roundType", "inputType")) {
                if (meta.path(field).asText("").isBlank()) {
                    issues.add(label + "/meta.json: 缺少 " + field);
                }
            }
            checkEnum(label, "roundType", meta, ROUND_TYPES, issues);
            checkEnum(label, "inputType", meta, INPUT_TYPES, issues);
            checkEnum(label, "format", meta, FORMATS, issues);
        }
    }

    private static void checkEnum(String label, String field, JsonNode meta, Set<String> allowed,
                                  List<String> issues) {
        if (meta.hasNonNull(field) && !allowed.contains(meta.path(field).asText())) {
            issues.add(label + "/meta.json: " + field + " 非法 " + meta.path(field).asText());
        }
    }

    private static void checkExpect(String label, String scene, JsonNode expect, List<String> issues) {
        Fields fields = FIELDS.get(scene);
        expect.fieldNames().forEachRemaining(key -> {
            if (!EXPECT_KEYS.contains(key)) {
                issues.add(label + "/expect.json: 未知键 " + key);
            }
        });
        for (JsonNode field : expect.path("requiredFields")) {
            String path = field.asText();
            if (!fields.top().contains(path) && !fields.numeric().contains(path) && !fields.lists().contains(path)) {
                issues.add(label + "/expect.json: requiredFields 路径非法 " + path);
            }
        }
        expect.path("maxItems").fields().forEachRemaining(entry -> {
            if (!fields.lists().contains(entry.getKey()) || entry.getValue().asInt() <= 0) {
                issues.add(label + "/expect.json: maxItems 非法 " + entry.getKey());
            }
        });
        expect.path("scoreRange").fields().forEachRemaining(entry -> {
            JsonNode range = entry.getValue();
            if (!fields.numeric().contains(entry.getKey()) || !range.isArray() || range.size() != 2
                    || range.get(0).asInt() > range.get(1).asInt()) {
                issues.add(label + "/expect.json: scoreRange 非法 " + entry.getKey());
            }
        });
        checkVocabulary(label, expect, "topicsMustInclude", TOPICS, issues);
        checkVocabulary(label, expect, "lossTagsMustInclude", LOSS_TAGS, issues);
        checkVocabulary(label, expect, "mustNotContainTags", LOSS_TAGS, issues);
        expect.path("mustMentionAny").forEach(node -> {
            if (node.asText().isBlank()) {
                issues.add(label + "/expect.json: mustMentionAny 含空关键词");
            }
        });
        String formula = expect.path("overallFormula").asText("");
        if (!formula.isEmpty() && (!"resume-analysis".equals(scene) || !"30/25/20/15/10".equals(formula))) {
            issues.add(label + "/expect.json: overallFormula 非法或场景不符 " + formula);
        }
        if (expect.has("skillsMin") && (!"jd-parse".equals(scene) || expect.path("skillsMin").asInt() <= 0)) {
            issues.add(label + "/expect.json: skillsMin 非法或场景不符");
        }
        if (expect.has("scoreDriftMax") && expect.path("scoreDriftMax").asInt() <= 0) {
            issues.add(label + "/expect.json: scoreDriftMax 必须为正");
        }
        if (expect.has("tagJaccardMin")
                && (expect.path("tagJaccardMin").asDouble() < 0 || expect.path("tagJaccardMin").asDouble() > 1)) {
            issues.add(label + "/expect.json: tagJaccardMin 需在 0~1");
        }
        boolean hasL1 = expect.path("requiredFields").size() > 0 || expect.path("scoreRange").size() > 0
                || expect.path("maxItems").size() > 0 || expect.path("quotesMustComeFromInput").asBoolean(false)
                || !formula.isEmpty();
        if (!hasL1) {
            issues.add(label + "/expect.json: 缺少 L1 硬断言");
        }
    }

    private static void checkVocabulary(String label, JsonNode expect, String field, Set<String> allowed,
                                        List<String> issues) {
        expect.path(field).forEach(node -> {
            if (!allowed.contains(node.asText())) {
                issues.add(label + "/expect.json: " + field + " 词表外 " + node.asText());
            }
        });
    }
}
