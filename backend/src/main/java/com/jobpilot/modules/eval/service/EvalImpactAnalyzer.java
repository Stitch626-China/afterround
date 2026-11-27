package com.jobpilot.modules.eval.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.modules.eval.core.EvalEngine;
import com.jobpilot.modules.eval.core.EvalSceneCatalog;
import com.jobpilot.modules.eval.core.EvalSceneCatalog.EvalScene;
import com.jobpilot.modules.eval.dto.PromptImpactSuggestion;
import com.jobpilot.modules.eval.entity.LlmEvalBaseline;
import com.jobpilot.modules.eval.mapper.LlmEvalBaselineMapper;
import com.jobpilot.modules.llm.service.LlmSceneService;
import com.jobpilot.modules.llm.service.LlmSceneService.SceneModel;
import com.jobpilot.modules.prompt.catalog.PromptCatalog;
import com.jobpilot.modules.prompt.catalog.PromptSpec;
import com.jobpilot.modules.prompt.service.PromptRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 影响面提示：当前配置与当前基线不一致时给出重建原因；只提示，不自动运行、不阻塞使用。
 * 「无基线」也作为原因返回，前端据此显示「尚无基线」而不是「已过期」。
 */
@Component
public class EvalImpactAnalyzer {

    private static final ObjectMapper M = new ObjectMapper();

    private final EvalSceneCatalog catalog;
    private final PromptCatalog promptCatalog;
    private final PromptRegistry registry;
    private final LlmSceneService sceneService;
    private final LlmEvalBaselineMapper baselineMapper;

    @Value("${app.eval.max-tokens:8192}")
    private int maxTokens = 8192;
    @Value("${app.eval.timeout-seconds:300}")
    private int timeoutSeconds = 300;

    public EvalImpactAnalyzer(EvalSceneCatalog catalog, PromptCatalog promptCatalog, PromptRegistry registry,
                              LlmSceneService sceneService, LlmEvalBaselineMapper baselineMapper) {
        this.catalog = catalog;
        this.promptCatalog = promptCatalog;
        this.registry = registry;
        this.sceneService = sceneService;
        this.baselineMapper = baselineMapper;
    }

    /** 提示词详情用：映射到评测场景后给出建议；无评测语料返回空。 */
    public List<PromptImpactSuggestion> suggestForPrompt(String promptKey) {
        PromptSpec spec = promptCatalog.get(promptKey);
        if (spec.evalScene() == null) {
            return List.of();
        }
        PromptImpactSuggestion suggestion = suggestion(spec.evalScene());
        return suggestion == null ? List.of() : List.of(suggestion);
    }

    public PromptImpactSuggestion suggestion(String sceneKey) {
        EvalScene scene = catalog.get(sceneKey);
        if (catalog.caseCount(scene.key()) == 0) {
            return null;
        }
        List<String> reasons = rebuildReasons(scene.key());
        if (reasons.isEmpty()) {
            return null;
        }
        LlmEvalBaseline baseline = currentBaseline(scene.key());
        return new PromptImpactSuggestion(scene.key(), catalog.caseCount(scene.key()), reasons,
                baseline == null ? null : baseline.getId());
    }

    /** 当前配置与当前基线逐项比较；无基线返回「尚无基线」，一致返回空列表。 */
    public List<String> rebuildReasons(String sceneKey) {
        EvalScene scene = catalog.get(sceneKey);
        List<String> reasons = new ArrayList<>();
        LlmEvalBaseline baseline = currentBaseline(sceneKey);
        if (baseline == null) {
            reasons.add("尚无基线");
            return reasons;
        }
        String promptHash = registry.template(scene.promptKey()).contentHash();
        if (!Objects.equals(promptHash, baseline.getPromptHash()) && !"legacy".equals(baseline.getPromptHash())) {
            reasons.add("提示词已更新");
        }
        SceneModel model = currentModel(scene);
        if (model == null) {
            reasons.add("模型未配置");
        } else {
            if (!"legacy".equals(baseline.getProviderKey())
                    && !Objects.equals(model.providerKey(), baseline.getProviderKey())) {
                reasons.add("Provider 已更换");
            }
            if (!Objects.equals(model.model(), baseline.getModel())) {
                reasons.add("模型已更换");
            }
        }
        if (!Objects.equals(catalog.corpusHash(sceneKey), baseline.getCorpusHash())) {
            reasons.add("评测语料已变化");
        }
        if (!Objects.equals(EvalEngine.VERSION, baseline.getEngineVersion())) {
            reasons.add("断言引擎已升级");
        }
        Map<String, Object> params = params(baseline.getParams());
        if (changed(params, "maxTokens", maxTokens) || changed(params, "timeoutSeconds", timeoutSeconds)
                || changed(params, "temperature", 0.2)) {
            reasons.add("生成参数已变化");
        }
        return reasons;
    }

    public LlmEvalBaseline currentBaseline(String sceneKey) {
        return baselineMapper.selectOne(Wrappers.<LlmEvalBaseline>lambdaQuery()
                .eq(LlmEvalBaseline::getScene, sceneKey)
                .eq(LlmEvalBaseline::getCurrent, true));
    }

    private SceneModel currentModel(EvalScene scene) {
        try {
            return scene.globalModel() ? sceneService.resolveGlobal() : sceneService.resolve(scene.llmScene());
        } catch (BizException ex) {
            return null;
        }
    }

    private static boolean changed(Map<String, Object> params, String key, Object current) {
        Object value = params.get(key);
        if (value == null) {
            return false;
        }
        if (value instanceof Number number && current instanceof Number expected) {
            return Double.compare(number.doubleValue(), expected.doubleValue()) != 0;
        }
        return !String.valueOf(value).equals(String.valueOf(current));
    }

    private static Map<String, Object> params(String json) {
        try {
            return M.readValue(json == null ? "{}" : json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }
}
