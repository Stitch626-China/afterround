package com.jobpilot.modules.eval.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.api.PageResult;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.modules.eval.core.EvalCase;
import com.jobpilot.modules.eval.core.EvalMetrics;
import com.jobpilot.modules.eval.core.EvalMetricsCalculator;
import com.jobpilot.modules.eval.core.EvalSceneCatalog;
import com.jobpilot.modules.eval.core.EvalSceneCatalog.EvalScene;
import com.jobpilot.modules.eval.core.EvalEngine;
import com.jobpilot.modules.eval.dto.CreateEvalRunRequest;
import com.jobpilot.modules.eval.dto.EvalAssertionResult;
import com.jobpilot.modules.eval.dto.EvalBaselineResult;
import com.jobpilot.modules.eval.dto.EvalCaseResult;
import com.jobpilot.modules.eval.dto.EvalRunDetail;
import com.jobpilot.modules.eval.dto.EvalRunSummary;
import com.jobpilot.modules.eval.dto.EvalSceneModel;
import com.jobpilot.modules.eval.dto.EvalScenePrompt;
import com.jobpilot.modules.eval.dto.EvalSceneSummary;
import com.jobpilot.modules.eval.entity.LlmEvalBaseline;
import com.jobpilot.modules.eval.entity.LlmEvalRun;
import com.jobpilot.modules.eval.entity.LlmEvalRunCase;
import com.jobpilot.modules.eval.mapper.LlmEvalBaselineMapper;
import com.jobpilot.modules.eval.mapper.LlmEvalRunCaseMapper;
import com.jobpilot.modules.eval.mapper.LlmEvalRunMapper;
import com.jobpilot.modules.llm.service.LlmSceneService;
import com.jobpilot.modules.llm.service.LlmSceneService.SceneModel;
import com.jobpilot.modules.prompt.service.PromptRegistry;
import com.jobpilot.modules.prompt.service.PromptTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 评测运行：创建快照、异步调度、进度 / 取消与详情聚合。始终由用户主动触发。 */
@Service
public class EvalRunService {

    private static final Logger log = LoggerFactory.getLogger(EvalRunService.class);
    private static final ObjectMapper M = new ObjectMapper();
    private static final int MAX_WORKER_RETRIES = 3;
    private static final List<String> ACTIVE_STATUS = List.of("PENDING", "PROCESSING");
    private static final List<String> TERMINAL_STATUS = List.of("COMPLETED", "FAILED", "CANCELLED");

    private final LlmEvalRunMapper runMapper;
    private final LlmEvalRunCaseMapper caseMapper;
    private final LlmEvalBaselineMapper baselineMapper;
    private final EvalSceneCatalog catalog;
    private final LlmSceneService sceneService;
    private final PromptRegistry registry;
    private final EvalRunner runner;
    private final EvalImpactAnalyzer analyzer;
    private final EvalBaselineService baselineService;
    private final ThreadPoolTaskExecutor executor;

    @Value("${app.eval.max-tokens:8192}")
    private int maxTokens = 8192;
    @Value("${app.eval.timeout-seconds:300}")
    private int timeoutSeconds = 300;
    @Value("${app.eval.repeat:1}")
    private int defaultRepeat = 1;

    public EvalRunService(LlmEvalRunMapper runMapper, LlmEvalRunCaseMapper caseMapper,
                          LlmEvalBaselineMapper baselineMapper,
                          EvalSceneCatalog catalog, LlmSceneService sceneService, PromptRegistry registry,
                          EvalRunner runner, EvalImpactAnalyzer analyzer, EvalBaselineService baselineService,
                          @Qualifier("evalTaskExecutor") ThreadPoolTaskExecutor executor) {
        this.runMapper = runMapper;
        this.caseMapper = caseMapper;
        this.baselineMapper = baselineMapper;
        this.catalog = catalog;
        this.sceneService = sceneService;
        this.registry = registry;
        this.runner = runner;
        this.analyzer = analyzer;
        this.baselineService = baselineService;
        this.executor = executor;
    }

    /** 3 个评测场景：语料、生效提示词、生效模型与最近一次运行；不自动跑、不阻塞使用。 */
    public List<EvalSceneSummary> scenes() {
        List<LlmEvalRun> recent = runMapper.selectList(Wrappers.<LlmEvalRun>lambdaQuery()
                .orderByDesc(LlmEvalRun::getCreatedAt).last("LIMIT 20"));
        return catalog.all().stream().map(scene -> {
            SceneModel model = null;
            try {
                model = scene.globalModel() ? sceneService.resolveGlobal() : sceneService.resolve(scene.llmScene());
            } catch (BizException ex) {
                log.debug("评测场景 {} 尚未配置模型: {}", scene.key(), ex.getMessage());
            }
            EvalRunSummary lastRun = recent.stream()
                    .filter(run -> parseScenes(run.getScenes()).contains(scene.key()))
                    .findFirst().map(this::summary).orElse(null);
            return new EvalSceneSummary(scene.key(), scene.name(), catalog.caseCount(scene.key()),
                    catalog.corpusHash(scene.key()), scene.promptKey(), registry.version(scene.promptKey()),
                    scene.globalModel() ? "GLOBAL" : "SCENE",
                    model == null ? null : model.providerKey(), model == null ? null : model.model(),
                    baselineService.current(scene.key()), lastRun, analyzer.rebuildReasons(scene.key()));
        }).toList();
    }

    /** 创建运行：校验并发与模型，快照提示词 / 模型 / 参数 / 语料，事务提交后异步执行。 */
    @Transactional
    public EvalRunDetail create(CreateEvalRunRequest request) {
        if (request.sourceRunId() != null) {
            return createPartial(request);
        }
        if (Boolean.TRUE.equals(request.retryErrorsOnly())) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "retryErrorsOnly 需要同时提供 sourceRunId");
        }
        List<String> scenes = normalizeScenes(request);
        if (runMapper.selectCount(Wrappers.<LlmEvalRun>lambdaQuery()
                .in(LlmEvalRun::getStatus, ACTIVE_STATUS)) > 0) {
            throw BizException.of(ErrorCode.EVAL_RUN_IN_PROGRESS);
        }
        int repeat = request.repeat() == null ? Math.max(1, defaultRepeat) : request.repeat();
        ArrayNode modelArray = M.createArrayNode();
        ArrayNode promptArray = M.createArrayNode();
        for (String key : scenes) {
            EvalScene scene = catalog.get(key);
            SceneModel model = scene.globalModel() ? sceneService.resolveGlobal()
                    : sceneService.resolve(scene.llmScene());
            ObjectNode modelNode = modelArray.addObject();
            modelNode.put("scene", key);
            modelNode.put("providerKey", model.providerKey());
            modelNode.put("model", model.model());
            PromptTemplate template = registry.template(scene.promptKey());
            ObjectNode promptNode = promptArray.addObject();
            promptNode.put("scene", key);
            promptNode.put("promptKey", scene.promptKey());
            promptNode.put("promptVersion", template.versionLabel());
            promptNode.put("promptHash", template.contentHash());
            promptNode.put("system", template.system());
            promptNode.put("user", template.user());
        }
        List<EvalCase> cases = catalog.cases(scenes);
        LlmEvalRun run = new LlmEvalRun();
        run.setUserId(1L);
        run.setStatus("PENDING");
        run.setPartial(false);
        run.setScenes(json(scenes));
        run.setModelSnapshot(modelArray.toString());
        run.setPromptSnapshot(promptArray.toString());
        run.setCorpusHash(catalog.combinedCorpusHash(scenes));
        run.setEngineVersion(EvalEngine.VERSION);
        run.setParams(params(repeat));
        run.setTotalCases(cases.size());
        run.setCompletedCases(0);
        run.setErrorCases(0);
        run.setRetryCount(0);
        run.setCancelRequested(false);
        run.setCreatedAt(OffsetDateTime.now());
        try {
            runMapper.insert(run);
        } catch (DuplicateKeyException ex) {
            throw BizException.of(ErrorCode.EVAL_RUN_IN_PROGRESS);
        }
        for (EvalCase evalCase : cases) {
            LlmEvalRunCase row = new LlmEvalRunCase();
            row.setRunId(run.getId());
            row.setScene(evalCase.scene());
            row.setCaseId(evalCase.id());
            row.setStatus("PENDING");
            row.setResults("[]");
            row.setScores("{}");
            row.setTopics("[]");
            row.setLossTags("[]");
            row.setCreatedAt(OffsetDateTime.now());
            row.setUpdatedAt(OffsetDateTime.now());
            caseMapper.insert(row);
        }
        Long runId = run.getId();
        afterCreate(runId);
        return detail(runId);
    }

    /** 部分重跑：复制源运行的模型 / 提示词 / 参数 / 语料快照，只重跑 ERROR 用例。 */
    private EvalRunDetail createPartial(CreateEvalRunRequest request) {
        if (request.scenes() != null && !request.scenes().isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "sourceRunId 与 scenes 二选一");
        }
        if (!Boolean.TRUE.equals(request.retryErrorsOnly())) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "部分重跑必须指定 retryErrorsOnly=true");
        }
        LlmEvalRun source = requireRun(request.sourceRunId());
        if (!"COMPLETED".equals(source.getStatus())) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "只能重跑已完成运行中的失败用例");
        }
        List<LlmEvalRunCase> errors = caseMapper.selectList(Wrappers.<LlmEvalRunCase>lambdaQuery()
                .eq(LlmEvalRunCase::getRunId, source.getId()).eq(LlmEvalRunCase::getStatus, "ERROR"));
        if (errors.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "源运行没有执行错误用例");
        }
        if (runMapper.selectCount(Wrappers.<LlmEvalRun>lambdaQuery()
                .in(LlmEvalRun::getStatus, ACTIVE_STATUS)) > 0) {
            throw BizException.of(ErrorCode.EVAL_RUN_IN_PROGRESS);
        }
        LlmEvalRun run = new LlmEvalRun();
        run.setUserId(1L);
        run.setStatus("PENDING");
        run.setPartial(true);
        run.setScenes(source.getScenes());
        run.setModelSnapshot(source.getModelSnapshot());
        run.setPromptSnapshot(source.getPromptSnapshot());
        run.setCorpusHash(source.getCorpusHash());
        run.setEngineVersion(source.getEngineVersion());
        run.setParams(source.getParams());
        run.setTotalCases(errors.size());
        run.setCompletedCases(0);
        run.setErrorCases(0);
        run.setRetryCount(0);
        run.setCancelRequested(false);
        run.setCreatedAt(OffsetDateTime.now());
        try {
            runMapper.insert(run);
        } catch (DuplicateKeyException ex) {
            throw BizException.of(ErrorCode.EVAL_RUN_IN_PROGRESS);
        }
        for (LlmEvalRunCase error : errors) {
            LlmEvalRunCase row = new LlmEvalRunCase();
            row.setRunId(run.getId());
            row.setScene(error.getScene());
            row.setCaseId(error.getCaseId());
            row.setStatus("PENDING");
            row.setResults("[]");
            row.setScores("{}");
            row.setTopics("[]");
            row.setLossTags("[]");
            row.setCreatedAt(OffsetDateTime.now());
            row.setUpdatedAt(OffsetDateTime.now());
            caseMapper.insert(row);
        }
        Long runId = run.getId();
        afterCreate(runId);
        return detail(runId);
    }

    private void afterCreate(Long runId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch(runId, 0);
                }
            });
        } else {
            dispatch(runId, 0);
        }
    }

    public PageResult<EvalRunSummary> list(Integer page, Integer size) {
        int current = PageResult.normalizePage(page);
        int pageSize = PageResult.normalizeSize(size);
        Page<LlmEvalRun> result = runMapper.selectPage(new Page<>(current, pageSize),
                Wrappers.<LlmEvalRun>lambdaQuery().orderByDesc(LlmEvalRun::getCreatedAt));
        return PageResult.of(result.getRecords().stream().map(this::summary).toList(),
                result.getTotal(), current, pageSize);
    }

    public EvalRunDetail detail(Long runId) {
        LlmEvalRun run = requireRun(runId);
        List<LlmEvalRunCase> rows = caseMapper.selectList(Wrappers.<LlmEvalRunCase>lambdaQuery()
                .eq(LlmEvalRunCase::getRunId, runId).orderByAsc(LlmEvalRunCase::getId));
        return new EvalRunDetail(run.getId(), run.getStatus(), parseScenes(run.getScenes()), run.getTotalCases(),
                run.getCompletedCases(), run.getErrorCases(), run.getPartial(), run.getError(), run.getCreatedAt(),
                run.getStartedAt(), run.getFinishedAt(), rows.stream().map(this::caseResult).toList(),
                models(run.getModelSnapshot()), prompts(run.getPromptSnapshot()), metricsOf(rows), baselines(runId));
    }

    /** 取消：PENDING 直接终结并清理；PROCESSING 只置标记，由 worker 在当前用例结束后收口。 */
    @Transactional
    public EvalRunDetail cancel(Long runId) {
        LlmEvalRun run = requireRun(runId);
        if (TERMINAL_STATUS.contains(run.getStatus())) {
            throw BizException.of(ErrorCode.EVAL_RUN_FINISHED);
        }
        if ("PENDING".equals(run.getStatus())) {
            caseMapper.update(null, Wrappers.<LlmEvalRunCase>lambdaUpdate()
                    .eq(LlmEvalRunCase::getRunId, runId).eq(LlmEvalRunCase::getStatus, "PENDING")
                    .set(LlmEvalRunCase::getStatus, "SKIPPED").set(LlmEvalRunCase::getError, "已取消")
                    .set(LlmEvalRunCase::getUpdatedAt, OffsetDateTime.now()));
            runMapper.update(null, Wrappers.<LlmEvalRun>lambdaUpdate().eq(LlmEvalRun::getId, runId)
                    .set(LlmEvalRun::getStatus, "CANCELLED").set(LlmEvalRun::getCancelRequested, true)
                    .set(LlmEvalRun::getError, "已取消").set(LlmEvalRun::getFinishedAt, OffsetDateTime.now())
                    .set(LlmEvalRun::getCompletedCases, 0).set(LlmEvalRun::getErrorCases, 0));
        } else {
            runMapper.update(null, Wrappers.<LlmEvalRun>lambdaUpdate().eq(LlmEvalRun::getId, runId)
                    .set(LlmEvalRun::getCancelRequested, true));
        }
        return detail(runId);
    }

    /** 提交 worker；worker 级异常最多自动重试 3 次，队列满直接失败。 */
    void dispatch(Long runId, int attempt) {
        try {
            executor.execute(() -> {
                try {
                    runner.run(runId);
                } catch (Throwable ex) {
                    log.warn("评测运行 {} 第 {} 次执行异常", runId, attempt + 1, ex);
                    LlmEvalRun run = runMapper.selectById(runId);
                    if (!(run != null && ACTIVE_STATUS.contains(run.getStatus()))) {
                        return;
                    }
                    if (attempt < MAX_WORKER_RETRIES) {
                        int retry = attempt + 1;
                        runMapper.update(null, Wrappers.<LlmEvalRun>lambdaUpdate().eq(LlmEvalRun::getId, runId)
                                .set(LlmEvalRun::getRetryCount, retry));
                        dispatch(runId, retry);
                    } else {
                        fail(runId, "评测执行失败：" + ex.getMessage());
                    }
                }
            });
        } catch (org.springframework.core.task.TaskRejectedException ex) {
            fail(runId, "评测任务队列已满，请稍后重试");
        }
    }

    private void fail(Long runId, String error) {
        LlmEvalRun latest = runMapper.selectById(runId);
        if (latest == null || !ACTIVE_STATUS.contains(latest.getStatus())) {
            return;
        }
        caseMapper.update(null, Wrappers.<LlmEvalRunCase>lambdaUpdate()
                .eq(LlmEvalRunCase::getRunId, runId).eq(LlmEvalRunCase::getStatus, "PENDING")
                .set(LlmEvalRunCase::getStatus, "SKIPPED").set(LlmEvalRunCase::getError, "评测已失败")
                .set(LlmEvalRunCase::getUpdatedAt, OffsetDateTime.now()));
        List<LlmEvalRunCase> rows = caseMapper.selectList(Wrappers.<LlmEvalRunCase>lambdaQuery()
                .eq(LlmEvalRunCase::getRunId, runId));
        int completed = (int) rows.stream()
                .filter(row -> !"PENDING".equals(row.getStatus()) && !"SKIPPED".equals(row.getStatus())).count();
        int errors = (int) rows.stream().filter(row -> "ERROR".equals(row.getStatus())).count();
        runMapper.update(null, Wrappers.<LlmEvalRun>lambdaUpdate().eq(LlmEvalRun::getId, runId)
                .in(LlmEvalRun::getStatus, ACTIVE_STATUS)
                .set(LlmEvalRun::getStatus, "FAILED").set(LlmEvalRun::getError, truncate(error))
                .set(LlmEvalRun::getFinishedAt, OffsetDateTime.now())
                .set(LlmEvalRun::getCompletedCases, completed).set(LlmEvalRun::getErrorCases, errors));
    }

    private List<String> normalizeScenes(CreateEvalRunRequest request) {
        List<String> scenes = request.scenes() == null ? List.of()
                : request.scenes().stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
        if (scenes.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "请选择至少一个评测场景");
        }
        scenes.forEach(catalog::get);
        return scenes;
    }

    private LlmEvalRun requireRun(Long runId) {
        LlmEvalRun run = runMapper.selectById(runId);
        if (run == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "评测运行不存在: " + runId);
        }
        return run;
    }

    private EvalRunSummary summary(LlmEvalRun run) {
        return new EvalRunSummary(run.getId(), run.getStatus(), parseScenes(run.getScenes()), run.getTotalCases(),
                run.getCompletedCases(), run.getErrorCases(), run.getPartial(), run.getError(), run.getCreatedAt(),
                run.getStartedAt(), run.getFinishedAt());
    }

    /** 该运行已创建的基线，供详情页展示「已设为基线」。 */
    private List<EvalBaselineResult> baselines(Long runId) {
        return baselineMapper.selectList(Wrappers.<LlmEvalBaseline>lambdaQuery()
                        .eq(LlmEvalBaseline::getRunId, runId).orderByAsc(LlmEvalBaseline::getScene))
                .stream().map(row -> new EvalBaselineResult(row.getScene(), true, row.getId(), null)).toList();
    }

    private EvalCaseResult caseResult(LlmEvalRunCase row) {
        return new EvalCaseResult(row.getScene(), row.getCaseId(), row.getStatus(), row.getL1Passed(),
                row.getL2Passed(), row.getL3Passed(), results(row.getResults()), scores(row.getScores()),
                row.getTokens(), row.getElapsedMs(), row.getError());
    }

    /** L1 按用例、L2 按断言条目；ERROR 计入 L1/L2 失败，SKIPPED / PENDING 不参与。 */
    static EvalMetrics metricsOf(List<LlmEvalRunCase> rows) {
        List<EvalMetricsCalculator.CaseResult> results = rows.stream().map(row -> {
            boolean error = "ERROR".equals(row.getStatus());
            int l2Total = 0;
            int l2Failed = 0;
            if (!error) {
                for (EvalAssertionResult result : results(row.getResults())) {
                    if ("L2".equals(result.layer())) {
                        l2Total++;
                        if (!result.passed()) {
                            l2Failed++;
                        }
                    }
                }
            }
            return new EvalMetricsCalculator.CaseResult(row.getStatus(),
                    error ? Boolean.FALSE : row.getL1Passed(),
                    error ? Integer.valueOf(1) : (l2Total == 0 ? null : l2Total),
                    error ? Integer.valueOf(1) : (l2Total == 0 ? null : l2Failed),
                    row.getTokens() == null ? null : row.getTokens().longValue(), row.getElapsedMs());
        }).toList();
        EvalMetrics metrics = EvalMetricsCalculator.of(results);
        boolean empty = metrics.l1Total() == null && metrics.l2Total() == null
                && metrics.totalTokens() == null && metrics.p95ElapsedMs() == null;
        return empty ? null : metrics;
    }

    static List<EvalAssertionResult> results(String json) {
        try {
            return M.readValue(json == null ? "[]" : json, new TypeReference<List<EvalAssertionResult>>() {
            });
        } catch (Exception ex) {
            return List.of();
        }
    }

    private Map<String, Double> scores(String json) {
        try {
            return M.readValue(json == null ? "{}" : json, new TypeReference<Map<String, Double>>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private List<EvalSceneModel> models(String json) {
        List<EvalSceneModel> list = new ArrayList<>();
        for (JsonNode node : array(json)) {
            list.add(new EvalSceneModel(node.path("scene").asText(), node.path("providerKey").asText(),
                    node.path("model").asText()));
        }
        return list;
    }

    private List<EvalScenePrompt> prompts(String json) {
        List<EvalScenePrompt> list = new ArrayList<>();
        for (JsonNode node : array(json)) {
            list.add(new EvalScenePrompt(node.path("scene").asText(), node.path("promptKey").asText(),
                    node.path("promptVersion").asText(), node.path("promptHash").asText()));
        }
        return list;
    }

    private List<String> parseScenes(String json) {
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

    private String params(int repeat) {
        ObjectNode params = M.createObjectNode();
        params.put("maxTokens", maxTokens);
        params.put("timeoutSeconds", timeoutSeconds);
        params.put("repeat", repeat);
        params.put("temperature", 0.2);
        return params.toString();
    }

    private static String json(Object value) {
        try {
            return M.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化评测快照失败", ex);
        }
    }

    private static String truncate(String text) {
        if (text == null) {
            return null;
        }
        String value = text.replaceAll("\\s+", " ").trim();
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
