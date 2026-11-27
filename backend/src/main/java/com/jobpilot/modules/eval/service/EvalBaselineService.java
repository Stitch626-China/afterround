package com.jobpilot.modules.eval.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.modules.eval.core.EvalMetrics;
import com.jobpilot.modules.eval.core.EvalSceneCatalog;
import com.jobpilot.modules.eval.dto.CreateEvalBaselineRequest;
import com.jobpilot.modules.eval.dto.EvalBaselineResult;
import com.jobpilot.modules.eval.dto.EvalBaselineSummary;
import com.jobpilot.modules.eval.entity.LlmEvalBaseline;
import com.jobpilot.modules.eval.entity.LlmEvalRun;
import com.jobpilot.modules.eval.entity.LlmEvalRunCase;
import com.jobpilot.modules.eval.mapper.LlmEvalBaselineMapper;
import com.jobpilot.modules.eval.mapper.LlmEvalRunCaseMapper;
import com.jobpilot.modules.eval.mapper.LlmEvalRunMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 基线：把已完成的运行「设为基线」只是贴标签，复用运行结果，不重跑。
 * 每个场景独立处理，可部分成功；历史保留，is_current 只标记当前基线。
 */
@Service
public class EvalBaselineService {

    private static final ObjectMapper M = new ObjectMapper();
    private static final EvalMetrics EMPTY_METRICS = new EvalMetrics(null, null, null, null, null,
            null, null, null, null, null);

    private final LlmEvalBaselineMapper baselineMapper;
    private final LlmEvalRunMapper runMapper;
    private final LlmEvalRunCaseMapper caseMapper;
    private final EvalSceneCatalog catalog;

    public EvalBaselineService(LlmEvalBaselineMapper baselineMapper, LlmEvalRunMapper runMapper,
                               LlmEvalRunCaseMapper caseMapper, EvalSceneCatalog catalog) {
        this.baselineMapper = baselineMapper;
        this.runMapper = runMapper;
        this.caseMapper = caseMapper;
        this.catalog = catalog;
    }

    /** 从已完成运行创建 / 更新基线；逐场景返回结果，存在 ERROR 的场景跳过。 */
    @Transactional
    public List<EvalBaselineResult> createFromRun(CreateEvalBaselineRequest request) {
        LlmEvalRun run = runMapper.selectById(request.runId());
        if (run == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "评测运行不存在: " + request.runId());
        }
        if (!"COMPLETED".equals(run.getStatus()) || Boolean.TRUE.equals(run.getPartial())) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "只能把已完成且非部分重跑的运行设为基线");
        }
        List<String> scenes = parseScenes(run.getScenes());
        List<LlmEvalRunCase> rows = caseMapper.selectList(Wrappers.<LlmEvalRunCase>lambdaQuery()
                .eq(LlmEvalRunCase::getRunId, run.getId()));
        Map<String, SceneMeta> snapshots = snapshots(run);
        List<EvalBaselineResult> results = new ArrayList<>();
        String label = request.label() == null || request.label().isBlank() ? null : request.label().trim();
        for (String scene : scenes) {
            List<LlmEvalRunCase> sceneRows = rows.stream().filter(row -> scene.equals(row.getScene())).toList();
            long errors = sceneRows.stream().filter(row -> "ERROR".equals(row.getStatus())).count();
            if (errors > 0) {
                results.add(new EvalBaselineResult(scene, false, null, "存在 " + errors + " 个执行错误用例，已跳过"));
                continue;
            }
            LlmEvalBaseline existing = baselineMapper.selectOne(Wrappers.<LlmEvalBaseline>lambdaQuery()
                    .eq(LlmEvalBaseline::getRunId, run.getId()).eq(LlmEvalBaseline::getScene, scene));
            SceneMeta meta = snapshots.getOrDefault(scene, new SceneMeta("legacy", "", "legacy", "legacy"));
            EvalMetrics metrics = EvalRunService.metricsOf(sceneRows);
            if (existing == null) {
                existing = new LlmEvalBaseline();
                existing.setUserId(1L);
                existing.setScene(scene);
                existing.setRunId(run.getId());
                existing.setLabel(label);
                existing.setProviderKey(meta.providerKey());
                existing.setModel(meta.model());
                existing.setPromptVersion(meta.promptVersion());
                existing.setPromptHash(meta.promptHash());
                existing.setCorpusHash(catalog.corpusHash(scene));
                existing.setEngineVersion(run.getEngineVersion());
                existing.setParams(run.getParams());
                existing.setMetrics(json(metrics == null ? EMPTY_METRICS : metrics));
                existing.setCurrent(true);
                existing.setCreatedAt(OffsetDateTime.now());
                clearCurrent(scene, null);
                baselineMapper.insert(existing);
            } else {
                existing.setLabel(label);
                existing.setMetrics(json(metrics == null ? EMPTY_METRICS : metrics));
                existing.setCurrent(true);
                clearCurrent(scene, existing.getId());
                baselineMapper.updateById(existing);
            }
            results.add(new EvalBaselineResult(scene, true, existing.getId(), null));
        }
        return results;
    }

    /** 当前基线在前，其余按创建时间倒序；可按场景筛选。 */
    public List<EvalBaselineSummary> list(String scene) {
        if (scene != null && !scene.isBlank()) {
            catalog.get(scene);
        }
        return baselineMapper.selectList(Wrappers.<LlmEvalBaseline>lambdaQuery()
                        .eq(scene != null && !scene.isBlank(), LlmEvalBaseline::getScene, scene)
                        .orderByDesc(LlmEvalBaseline::getCurrent)
                        .orderByDesc(LlmEvalBaseline::getCreatedAt))
                .stream().map(this::summary).toList();
    }

    /** 只切换 is_current，不重跑、不改历史。 */
    @Transactional
    public EvalBaselineSummary activate(Long baselineId) {
        LlmEvalBaseline target = baselineMapper.selectById(baselineId);
        if (target == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "基线不存在: " + baselineId);
        }
        clearCurrent(target.getScene(), target.getId());
        target.setCurrent(true);
        baselineMapper.updateById(target);
        return summary(target);
    }

    private void clearCurrent(String scene, Long keepId) {
        baselineMapper.update(null, Wrappers.<LlmEvalBaseline>lambdaUpdate()
                .eq(LlmEvalBaseline::getScene, scene)
                .ne(keepId != null, LlmEvalBaseline::getId, keepId)
                .set(LlmEvalBaseline::getCurrent, false));
    }

    private EvalBaselineSummary summary(LlmEvalBaseline row) {
        return new EvalBaselineSummary(row.getId(), row.getScene(), row.getRunId(), row.getLabel(),
                row.getProviderKey(), row.getModel(), row.getPromptVersion(), row.getPromptHash(),
                row.getCorpusHash(), row.getEngineVersion(), params(row.getParams()), metrics(row.getMetrics()),
                Boolean.TRUE.equals(row.getCurrent()), row.getCreatedAt());
    }

    private Map<String, SceneMeta> snapshots(LlmEvalRun run) {
        Map<String, String[]> models = new HashMap<>();
        for (JsonNode node : array(run.getModelSnapshot())) {
            models.put(node.path("scene").asText(), new String[]{node.path("providerKey").asText(),
                    node.path("model").asText()});
        }
        Map<String, SceneMeta> map = new HashMap<>();
        for (JsonNode node : array(run.getPromptSnapshot())) {
            String scene = node.path("scene").asText();
            String[] model = models.getOrDefault(scene, new String[]{"legacy", ""});
            map.put(scene, new SceneMeta(model[0], model[1], node.path("promptVersion").asText(),
                    node.path("promptHash").asText()));
        }
        return map;
    }

    private record SceneMeta(String providerKey, String model, String promptVersion, String promptHash) {
    }

    private Map<String, Object> params(String json) {
        try {
            return M.readValue(json == null ? "{}" : json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private EvalMetrics metrics(String json) {
        try {
            return M.readValue(json == null ? "{}" : json, EvalMetrics.class);
        } catch (Exception ex) {
            return EMPTY_METRICS;
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

    private static String json(Object value) {
        try {
            return M.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化基线指标失败", ex);
        }
    }
}
