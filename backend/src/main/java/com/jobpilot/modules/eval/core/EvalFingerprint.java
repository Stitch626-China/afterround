package com.jobpilot.modules.eval.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 可比性指纹：场景、模型、参数、语料与引擎一致才可对比；提示词是被测变量，不参与判定。
 * 历史导入基线的 Provider 未知，记为 legacy，按模型比较。
 */
public record EvalFingerprint(String scene, String providerKey, String model, String corpusHash,
                              String engineVersion, Map<String, Object> params) {

    private static final String LEGACY_PROVIDER = "legacy";

    public List<String> differences(EvalFingerprint other) {
        List<String> reasons = new ArrayList<>();
        if (!scene.equals(other.scene)) {
            reasons.add("场景不同：" + scene + " vs " + other.scene);
        }
        if (!LEGACY_PROVIDER.equals(providerKey) && !LEGACY_PROVIDER.equals(other.providerKey)
                && !providerKey.equals(other.providerKey)) {
            reasons.add("Provider 不同：" + providerKey + " vs " + other.providerKey);
        }
        if (!model.equals(other.model)) {
            reasons.add("模型不同：" + model + " vs " + other.model);
        }
        if (!Objects.equals(normalize(params), normalize(other.params))) {
            reasons.add("生成参数不同");
        }
        if (!corpusHash.equals(other.corpusHash)) {
            reasons.add("语料不同");
        }
        if (!engineVersion.equals(other.engineVersion)) {
            reasons.add("断言引擎版本不同");
        }
        return reasons;
    }

    private static Map<String, Object> normalize(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }
}
