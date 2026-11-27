package com.jobpilot.modules.eval.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.common.llm.ChatMessage;
import com.jobpilot.common.llm.ChatResult;
import com.jobpilot.common.llm.LlmClient;
import com.jobpilot.common.llm.LlmException;
import com.jobpilot.common.llm.StructuredOutputInvoker;
import com.jobpilot.infrastructure.llm.LlmCallLogger;
import com.jobpilot.modules.eval.core.EvalCase;
import com.jobpilot.modules.eval.core.EvalSceneCatalog;
import com.jobpilot.modules.eval.core.EvalSceneCatalog.EvalScene;
import com.jobpilot.modules.eval.core.EvalSnapshot;
import com.jobpilot.modules.eval.core.Expectations;
import com.jobpilot.modules.eval.entity.LlmEvalRun;
import com.jobpilot.modules.eval.entity.LlmEvalRunCase;
import com.jobpilot.modules.eval.mapper.LlmEvalRunCaseMapper;
import com.jobpilot.modules.eval.mapper.LlmEvalRunMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientRequestException;

/**
 * 评测 worker：逐用例调用、断言、落库与进度更新；用例失败不重试，连续 3 次上游故障熔断。
 *
 * <p>模型与提示词使用运行创建时的快照，运行中途改配置不影响本次。
 */
@Component
public class EvalRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);
    private static final ObjectMapper M = new ObjectMapper();
    private static final int BREAKER_THRESHOLD = 3;

    private final LlmEvalRunMapper runMapper;
    private final LlmEvalRunCaseMapper caseMapper;
    private final EvalSceneCatalog catalog;
    private final EvalClientFactory clientFactory;
    private final LlmCallLogger callLogger;

    public EvalRunner(LlmEvalRunMapper runMapper, LlmEvalRunCaseMapper caseMapper,
                      EvalSceneCatalog catalog, EvalClientFactory clientFactory, LlmCallLogger callLogger) {
        this.runMapper = runMapper;
        this.caseMapper = caseMapper;
        this.catalog = catalog;
        this.clientFactory = clientFactory;
        this.callLogger = callLogger;
    }

    private record PromptSnapshot(String promptKey, String promptVersion, String system, String user) {
    }

    private record ModelSnapshot(String providerKey, String model) {
    }

    private record Outcome(String status, Boolean l1Passed, Boolean l2Passed, Boolean l3Passed,
                           List<Expectations.Result> results, EvalSnapshot snapshot,
                           Integer tokens, long elapsedMs, String error, boolean providerError) {
    }

    public void run(Long runId) {
        LlmEvalRun run = runMapper.selectById(runId);
        if (run == null || !active(run.getStatus())) {
            return;
        }
        if (Boolean.TRUE.equals(run.getCancelRequested())) {
            finish(runId, "CANCELLED", "已取消", "已取消");
            return;
        }
        runMapper.update(null, Wrappers.<LlmEvalRun>lambdaUpdate()
                .eq(LlmEvalRun::getId, runId).eq(LlmEvalRun::getStatus, "PENDING")
                .set(LlmEvalRun::getStatus, "PROCESSING").set(LlmEvalRun::getStartedAt, OffsetDateTime.now()));

        Map<String, PromptSnapshot> prompts = prompts(run.getPromptSnapshot());
        Map<String, ModelSnapshot> models = models(run.getModelSnapshot());
        int repeat = repeat(run.getParams());
        List<LlmEvalRunCase> cases = caseMapper.selectList(Wrappers.<LlmEvalRunCase>lambdaQuery()
                .eq(LlmEvalRunCase::getRunId, runId).eq(LlmEvalRunCase::getStatus, "PENDING")
                .orderByAsc(LlmEvalRunCase::getId));
        int consecutiveProviderErrors = 0;
        for (LlmEvalRunCase row : cases) {
            LlmEvalRun latest = runMapper.selectById(runId);
            if (latest == null) {
                log.info("评测运行 {} 已删除，终止", runId);
                return;
            }
            if (Boolean.TRUE.equals(latest.getCancelRequested())) {
                finish(runId, "CANCELLED", "已取消", "已取消");
                return;
            }
            EvalScene scene = catalog.get(row.getScene());
            ModelSnapshot model = models.get(scene.key());
            EvalCase evalCase = catalog.cases(List.of(row.getScene())).stream()
                    .filter(item -> item.id().equals(row.getCaseId())).findFirst().orElse(null);
            if (evalCase == null) {
                save(row, new Outcome("SKIPPED", null, null, null, List.of(), null, null, 0,
                        "语料已不存在", false), runId, scene, model);
                continue;
            }
            Outcome outcome = execute(scene, evalCase, model, prompts.get(scene.key()), repeat);
            save(row, outcome, runId, scene, model);
            consecutiveProviderErrors = outcome.providerError() ? consecutiveProviderErrors + 1 : 0;
            log.info("评测 {}/{} {} -> {} {}", runId, evalCase.scene(), evalCase.id(), outcome.status(),
                    outcome.error() == null ? "" : outcome.error());
            if (consecutiveProviderErrors >= BREAKER_THRESHOLD) {
                finish(runId, "FAILED", "连续 " + BREAKER_THRESHOLD + " 个用例因模型服务错误失败："
                        + outcome.error(), "模型服务连续失败，跳过");
                return;
            }
        }
        finish(runId, "COMPLETED", null, null);
    }

    private Outcome execute(EvalScene scene, EvalCase evalCase, ModelSnapshot model,
                            PromptSnapshot prompt, int repeat) {
        long start = System.nanoTime();
        List<Expectations.Result> results = new ArrayList<>();
        JsonNode actual = null;
        Integer tokens = 0;
        try {
            LlmClient client = clientFactory.get(model.providerKey());
            StructuredOutputInvoker invoker = new StructuredOutputInvoker(client);
            List<ChatMessage> messages = messages(scene, evalCase, prompt);
            StructuredOutputInvoker.Parsed<?> first = invoker.invokeWithUsage(model.model(), messages,
                    scene.outputType());
            actual = M.valueToTree(first.value());
            tokens = addTokens(tokens, first.result());
            results.addAll(Expectations.evaluate(evalCase, actual));
            if (repeat > 1) {
                StructuredOutputInvoker.Parsed<?> second = invoker.invokeWithUsage(model.model(), messages,
                        scene.outputType());
                tokens = addTokens(tokens, second.result());
                results.addAll(Expectations.stability(evalCase, actual, M.valueToTree(second.value())));
            }
            boolean passed = results.stream().allMatch(Expectations.Result::passed);
            return new Outcome(passed ? "PASS" : "FAIL", layerPassed(results, "L1"), layerPassed(results, "L2"),
                    layerPassed(results, "L3"), results, EvalSnapshot.of(scene.key(), actual), tokens,
                    elapsed(start), passed ? null : failedNames(results), false);
        } catch (Exception ex) {
            boolean graceful = evalCase.flag("expectGraceful");
            String message = truncate(ex.getMessage());
            return new Outcome(graceful ? "GRACEFUL" : "ERROR", graceful ? null : Boolean.FALSE,
                    graceful ? null : Boolean.FALSE, null, List.of(), null, null, elapsed(start), message,
                    !graceful && providerError(ex));
        }
    }

    private void save(LlmEvalRunCase row, Outcome outcome, Long runId, EvalScene scene, ModelSnapshot model) {
        LlmEvalRunCase update = new LlmEvalRunCase();
        update.setId(row.getId());
        update.setStatus(outcome.status());
        update.setL1Passed(outcome.l1Passed());
        update.setL2Passed(outcome.l2Passed());
        update.setL3Passed(outcome.l3Passed());
        update.setResults(json(outcome.results()));
        EvalSnapshot snapshot = outcome.snapshot() == null
                ? new EvalSnapshot(Map.of(), java.util.Set.of(), java.util.Set.of()) : outcome.snapshot();
        update.setScores(json(snapshot.scores()));
        update.setTopics(json(snapshot.topics()));
        update.setLossTags(json(snapshot.lossTags()));
        update.setTokens(outcome.tokens());
        update.setElapsedMs(outcome.elapsedMs());
        update.setError(outcome.error());
        update.setUpdatedAt(OffsetDateTime.now());
        caseMapper.updateById(update);

        callLogger.log(logScene(scene), model == null ? "" : model.providerKey(),
                model == null ? "" : model.model(), null, null, outcome.tokens(),
                outcome.elapsedMs(), !"ERROR".equals(outcome.status()), outcome.error());
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<LlmEvalRun> progress =
                Wrappers.<LlmEvalRun>lambdaUpdate().eq(LlmEvalRun::getId, runId)
                        .setSql("completed_cases = completed_cases + 1");
        if ("ERROR".equals(outcome.status())) {
            progress.setSql("error_cases = error_cases + 1");
        }
        runMapper.update(null, progress);
    }

    /** 结束运行并清理剩余 PENDING；计数按实际用例重算，避免重试 / 取消造成漂移。 */
    private void finish(Long runId, String status, String error, String pendingMessage) {
        caseMapper.update(null, Wrappers.<LlmEvalRunCase>lambdaUpdate()
                .eq(LlmEvalRunCase::getRunId, runId).eq(LlmEvalRunCase::getStatus, "PENDING")
                .set(LlmEvalRunCase::getStatus, "SKIPPED").set(LlmEvalRunCase::getError, pendingMessage)
                .set(LlmEvalRunCase::getUpdatedAt, OffsetDateTime.now()));
        List<LlmEvalRunCase> all = caseMapper.selectList(Wrappers.<LlmEvalRunCase>lambdaQuery()
                .eq(LlmEvalRunCase::getRunId, runId));
        int completed = (int) all.stream()
                .filter(row -> !"PENDING".equals(row.getStatus()) && !"SKIPPED".equals(row.getStatus())).count();
        int errors = (int) all.stream().filter(row -> "ERROR".equals(row.getStatus())).count();
        runMapper.update(null, Wrappers.<LlmEvalRun>lambdaUpdate().eq(LlmEvalRun::getId, runId)
                .set(LlmEvalRun::getStatus, status).set(LlmEvalRun::getError, error)
                .set(LlmEvalRun::getFinishedAt, OffsetDateTime.now())
                .set(LlmEvalRun::getCompletedCases, completed).set(LlmEvalRun::getErrorCases, errors));
    }

    private static boolean active(String status) {
        return "PENDING".equals(status) || "PROCESSING".equals(status);
    }

    private static Boolean layerPassed(List<Expectations.Result> results, String layer) {
        List<Expectations.Result> matched = results.stream().filter(result -> layer.equals(result.layer())).toList();
        return matched.isEmpty() ? null : matched.stream().allMatch(Expectations.Result::passed);
    }

    private static String failedNames(List<Expectations.Result> results) {
        String text = results.stream().filter(result -> !result.passed())
                .map(result -> result.layer() + ":" + result.name() + "(" + result.detail() + ")")
                .reduce((left, right) -> left + "; " + right).orElse("未通过");
        return truncate(text);
    }

    private static int addTokens(Integer total, ChatResult result) {
        int current = total == null ? 0 : total;
        return result == null || result.totalTokens() == null ? current : current + result.totalTokens();
    }

    private static long elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        String value = text.replaceAll("\\s+", " ").trim();
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private static String json(Object value) {
        try {
            return M.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化评测结果失败", ex);
        }
    }

    private static List<ChatMessage> messages(EvalScene scene, EvalCase evalCase, PromptSnapshot prompt) {
        String user = prompt.user();
        if ("review-analyze".equals(scene.key())) {
            user = user.replace("{{companyName}}", value(evalCase.text("companyName")))
                    .replace("{{jobTitle}}", value(evalCase.text("jobTitle")))
                    .replace("{{roundType}}", value(evalCase.text("roundType")))
                    .replace("{{interviewerRole}}", value(evalCase.text("interviewerRole")))
                    .replace("{{companyIntro}}", value(evalCase.optionalFile("company-intro.txt")))
                    .replace("{{jdText}}", value(evalCase.optionalFile("jd.txt")))
                    .replace("{{resumeText}}", value(evalCase.optionalFile("resume.txt")))
                    .replace("{{text}}", evalCase.input());
        } else if ("resume-analysis".equals(scene.key())) {
            user = user.replace("{{resumeText}}", evalCase.input())
                    .replace("{{marketDirections}}", "（岗位库暂无可统计的在招岗位，按简历本身判断）");
        } else {
            user = user.replace("{{jdText}}", evalCase.input());
        }
        return List.of(ChatMessage.system(prompt.system()), ChatMessage.user(user));
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "未填写" : value;
    }

    /** 熔断只统计上游连接 / 超时 / 5xx / 429；JSON 解析失败不算 Provider 故障。 */
    private static boolean providerError(Throwable ex) {
        Throwable current = ex;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof LlmException llm) {
                String message = llm.getMessage() == null ? "" : llm.getMessage();
                // 结构化输出重试会把上游错误包进「结构化输出连续 3 次失败：<原因>」，按原因判定
                if (message.startsWith("结构化输出连续")) {
                    return providerCause(message);
                }
                if (llm.status() == 429 || llm.status() >= 500) {
                    return true;
                }
            }
            if (current instanceof WebClientRequestException || current instanceof TimeoutException
                    || current instanceof java.net.ConnectException
                    || current instanceof java.net.SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean providerCause(String message) {
        return message.contains("HTTP 429") || message.matches("(?s).*HTTP 5\\d\\d.*")
                || message.contains("Connection refused") || message.contains("timed out")
                || message.contains("Timeout") || message.contains("UnknownHost");
    }

    private static Map<String, PromptSnapshot> prompts(String json) {
        Map<String, PromptSnapshot> map = new HashMap<>();
        for (JsonNode node : array(json)) {
            map.put(node.path("scene").asText(), new PromptSnapshot(node.path("promptKey").asText(),
                    node.path("promptVersion").asText(), node.path("system").asText(), node.path("user").asText()));
        }
        return map;
    }

    private static Map<String, ModelSnapshot> models(String json) {
        Map<String, ModelSnapshot> map = new HashMap<>();
        for (JsonNode node : array(json)) {
            map.put(node.path("scene").asText(), new ModelSnapshot(node.path("providerKey").asText(),
                    node.path("model").asText()));
        }
        return map;
    }

    private static JsonNode array(String json) {
        try {
            JsonNode node = M.readTree(json == null ? "[]" : json);
            return node.isArray() ? node : M.createArrayNode();
        } catch (Exception ex) {
            return M.createArrayNode();
        }
    }

    private static int repeat(String params) {
        try {
            return Math.max(1, M.readTree(params == null ? "{}" : params).path("repeat").asInt(1));
        } catch (Exception ex) {
            return 1;
        }
    }

    static String logScene(EvalScene scene) {
        return switch (scene.key()) {
            case "resume-analysis" -> "EVAL_RESUME_ANALYZE";
            case "review-analyze" -> "EVAL_REVIEW_ANALYZE";
            default -> "EVAL_JD_PARSE";
        };
    }
}
