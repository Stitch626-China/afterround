package com.jobpilot.modules.eval.service;

import java.time.OffsetDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.modules.eval.core.EvalEngine;
import com.jobpilot.modules.eval.core.EvalMetrics;
import com.jobpilot.modules.eval.core.EvalMetricsCalculator;
import com.jobpilot.modules.eval.core.EvalSceneCatalog;
import com.jobpilot.modules.eval.entity.LlmEvalBaseline;
import com.jobpilot.modules.eval.entity.LlmEvalRun;
import com.jobpilot.modules.eval.entity.LlmEvalRunCase;
import com.jobpilot.modules.eval.mapper.LlmEvalBaselineMapper;
import com.jobpilot.modules.eval.mapper.LlmEvalRunCaseMapper;
import com.jobpilot.modules.eval.mapper.LlmEvalRunMapper;
import com.jobpilot.modules.prompt.service.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 旧 JSON 基线一次性导入：只在「基线表为空且没有 COMPLETED 运行」时执行。
 *
 * <p>创建合成运行 + 逐用例 + 基线；旧数据没有 L1/L2 明细，对应通过率保持 null（前端显示「无数据」）。
 * promptVersion 与当前内置一致才写当前内容 hash，否则写 legacy，避免 JD / 复盘误报过期。
 */
@Component
public class LegacyBaselineImporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LegacyBaselineImporter.class);
    private static final ObjectMapper M = new ObjectMapper();

    private final LlmEvalBaselineMapper baselineMapper;
    private final LlmEvalRunMapper runMapper;
    private final LlmEvalRunCaseMapper caseMapper;
    private final EvalSceneCatalog catalog;
    private final PromptRegistry registry;

    public LegacyBaselineImporter(LlmEvalBaselineMapper baselineMapper, LlmEvalRunMapper runMapper,
                                  LlmEvalRunCaseMapper caseMapper, EvalSceneCatalog catalog,
                                  PromptRegistry registry) {
        this.baselineMapper = baselineMapper;
        this.runMapper = runMapper;
        this.caseMapper = caseMapper;
        this.catalog = catalog;
        this.registry = registry;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        if (baselineMapper.selectCount(null) > 0 || runMapper.selectCount(Wrappers.<LlmEvalRun>lambdaQuery()
                .eq(LlmEvalRun::getStatus, "COMPLETED")) > 0) {
            return;
        }
        int imported = 0;
        for (EvalSceneCatalog.EvalScene scene : catalog.all()) {
            ClassPathResource resource = new ClassPathResource("llm-eval/baselines/" + scene.key() + ".json");
            if (!resource.exists()) {
                continue;
            }
            imported += importScene(scene, M.readTree(resource.getInputStream()));
        }
        if (imported > 0) {
            log.info("已导入 {} 个历史 JSON 基线（旧数据无 L1/L2 明细）", imported);
        }
    }

    private int importScene(EvalSceneCatalog.EvalScene scene, JsonNode root) {
        JsonNode cases = root.path("cases");
        String model = root.path("model").asText("");
        String promptVersion = root.path("promptVersion").asText("legacy");
        String engineVersion = root.path("engineVersion").asText(EvalEngine.VERSION);
        String params = root.path("params").isObject() ? root.path("params").toString() : "{}";
        String promptHash = registry.version(scene.promptKey()).equals(promptVersion)
                ? registry.template(scene.promptKey()).contentHash() : "legacy";
        if (cases.isEmpty()) {
            return 0;
        }

        LlmEvalRun run = new LlmEvalRun();
        run.setUserId(1L);
        run.setStatus("COMPLETED");
        run.setPartial(false);
        run.setScenes("[\"" + scene.key() + "\"]");
        run.setModelSnapshot("[{\"scene\":\"" + scene.key() + "\",\"providerKey\":\"legacy\",\"model\":\""
                + escape(model) + "\"}]");
        run.setPromptSnapshot("[{\"scene\":\"" + scene.key() + "\",\"promptKey\":\"" + scene.promptKey()
                + "\",\"promptVersion\":\"" + escape(promptVersion) + "\",\"promptHash\":\"" + promptHash
                + "\",\"system\":\"\",\"user\":\"\"}]");
        run.setCorpusHash(catalog.corpusHash(scene.key()));
        run.setEngineVersion(engineVersion);
        run.setParams(params);
        run.setTotalCases(cases.size());
        run.setCompletedCases(cases.size());
        run.setErrorCases(0);
        run.setRetryCount(0);
        run.setCancelRequested(false);
        run.setStartedAt(OffsetDateTime.now());
        run.setFinishedAt(OffsetDateTime.now());
        run.setCreatedAt(OffsetDateTime.now());
        runMapper.insert(run);

        for (var entry : cases.properties()) {
            JsonNode node = entry.getValue();
            LlmEvalRunCase row = new LlmEvalRunCase();
            row.setRunId(run.getId());
            row.setScene(scene.key());
            row.setCaseId(entry.getKey());
            row.setStatus(node.path("status").asText("PASS"));
            row.setResults("[]");
            row.setScores(node.path("scores").isObject() ? node.path("scores").toString() : "{}");
            row.setTopics(node.path("topics").isArray() ? node.path("topics").toString() : "[]");
            row.setLossTags(node.path("lossTags").isArray() ? node.path("lossTags").toString() : "[]");
            row.setTokens(node.path("tokens").isNumber() ? node.path("tokens").asInt() : null);
            row.setElapsedMs(node.path("elapsedMs").isNumber() ? node.path("elapsedMs").asLong() : null);
            row.setCreatedAt(OffsetDateTime.now());
            row.setUpdatedAt(OffsetDateTime.now());
            caseMapper.insert(row);
        }

        List<LlmEvalRunCase> rows = caseMapper.selectList(Wrappers.<LlmEvalRunCase>lambdaQuery()
                .eq(LlmEvalRunCase::getRunId, run.getId()));
        EvalMetrics metrics = EvalMetricsCalculator.of(rows.stream()
                .map(row -> new EvalMetricsCalculator.CaseResult(row.getStatus(), null, null, null,
                        row.getTokens() == null ? null : row.getTokens().longValue(), row.getElapsedMs()))
                .toList());

        LlmEvalBaseline baseline = new LlmEvalBaseline();
        baseline.setUserId(1L);
        baseline.setScene(scene.key());
        baseline.setRunId(run.getId());
        baseline.setLabel("历史基线导入");
        baseline.setProviderKey("legacy");
        baseline.setModel(model);
        baseline.setPromptVersion(promptVersion);
        baseline.setPromptHash(promptHash);
        baseline.setCorpusHash(catalog.corpusHash(scene.key()));
        baseline.setEngineVersion(engineVersion);
        baseline.setParams(params);
        baseline.setMetrics(json(metrics));
        baseline.setCurrent(true);
        baseline.setCreatedAt(OffsetDateTime.now());
        baselineMapper.insert(baseline);
        return 1;
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String json(Object value) {
        try {
            return M.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化导入基线指标失败", ex);
        }
    }
}
