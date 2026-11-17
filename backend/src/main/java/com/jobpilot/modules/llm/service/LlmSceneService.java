package com.jobpilot.modules.llm.service;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.modules.llm.dto.BindSceneModelRequest;
import com.jobpilot.modules.llm.dto.LlmScene;
import com.jobpilot.modules.llm.dto.SceneBindingResponse;
import com.jobpilot.modules.llm.entity.LlmGlobalSetting;
import com.jobpilot.modules.llm.entity.LlmModel;
import com.jobpilot.modules.llm.entity.LlmProvider;
import com.jobpilot.modules.llm.entity.LlmSceneSetting;
import com.jobpilot.modules.llm.mapper.LlmGlobalSettingMapper;
import com.jobpilot.modules.llm.mapper.LlmModelMapper;
import com.jobpilot.modules.llm.mapper.LlmProviderMapper;
import com.jobpilot.modules.llm.mapper.LlmSceneSettingMapper;
import org.springframework.stereotype.Service;

/**
 * 场景级模型绑定（FRX.11）：未显式配置时回落全局默认，保存即生效。
 */
@Service
public class LlmSceneService {

    private final LlmSceneSettingMapper sceneMapper;
    private final LlmProviderMapper providerMapper;
    private final LlmModelMapper modelMapper;
    private final LlmGlobalSettingMapper globalMapper;

    public LlmSceneService(LlmSceneSettingMapper sceneMapper, LlmProviderMapper providerMapper,
                           LlmModelMapper modelMapper, LlmGlobalSettingMapper globalMapper) {
        this.sceneMapper = sceneMapper;
        this.providerMapper = providerMapper;
        this.modelMapper = modelMapper;
        this.globalMapper = globalMapper;
    }

    /** 全部场景：显式绑定 + 回落后的生效值。 */
    public List<SceneBindingResponse> list() {
        LlmGlobalSetting global = globalMapper.selectById(1);
        Map<String, LlmSceneSetting> settings = sceneMapper.selectList(null).stream()
                .collect(Collectors.toMap(LlmSceneSetting::getScene, Function.identity()));
        return Arrays.stream(LlmScene.values())
                .map(scene -> binding(scene, settings.get(scene.name()), global))
                .toList();
    }

    /** 保存场景绑定：Provider 必须启用，Model 必须属于该 Provider 且启用。 */
    public SceneBindingResponse bind(LlmScene scene, BindSceneModelRequest request) {
        LlmProvider provider = providerMapper.selectOne(Wrappers.<LlmProvider>lambdaQuery()
                .eq(LlmProvider::getProviderKey, request.providerKey()));
        if (provider == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "Provider 不存在: " + request.providerKey());
        }
        if (!Boolean.TRUE.equals(provider.getEnabled())) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "Provider 已禁用: " + request.providerKey());
        }
        LlmModel model = modelMapper.selectOne(Wrappers.<LlmModel>lambdaQuery()
                .eq(LlmModel::getProviderId, provider.getId())
                .eq(LlmModel::getModelName, request.model()));
        if (model == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "模型不属于该 Provider: " + request.model());
        }
        if (!Boolean.TRUE.equals(model.getEnabled())) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "模型已禁用: " + request.model());
        }

        LlmSceneSetting entity = sceneMapper.selectOne(Wrappers.<LlmSceneSetting>lambdaQuery()
                .eq(LlmSceneSetting::getScene, scene.name()));
        if (entity == null) {
            entity = new LlmSceneSetting();
            entity.setUserId(1L);
            entity.setScene(scene.name());
            entity.setProviderKey(request.providerKey());
            entity.setModel(request.model());
            sceneMapper.insert(entity);
        } else {
            entity.setProviderKey(request.providerKey());
            entity.setModel(request.model());
            entity.setUpdatedAt(OffsetDateTime.now());
            sceneMapper.updateById(entity);
        }
        return binding(scene, entity, globalMapper.selectById(1));
    }

    /** 清除绑定（幂等），返回回落后的生效值。 */
    public SceneBindingResponse unbind(LlmScene scene) {
        sceneMapper.delete(Wrappers.<LlmSceneSetting>lambdaQuery()
                .eq(LlmSceneSetting::getScene, scene.name()));
        return binding(scene, null, globalMapper.selectById(1));
    }

    /**
     * 供业务调用解析实际模型：优先场景绑定，其次全局默认；都没有时给出可操作提示。
     * JD 解析与简历对比不属于枚举场景，由调用方自行取全局默认。
     */
    public SceneModel resolve(LlmScene scene) {
        LlmSceneSetting setting = sceneMapper.selectOne(Wrappers.<LlmSceneSetting>lambdaQuery()
                .eq(LlmSceneSetting::getScene, scene.name()));
        if (setting != null) {
            return new SceneModel(setting.getProviderKey(), setting.getModel());
        }
        LlmGlobalSetting global = globalMapper.selectById(1);
        if (global == null || blank(global.getDefaultProviderKey()) || blank(global.getDefaultModel())) {
            throw BizException.of(ErrorCode.BAD_REQUEST,
                    "尚未设置默认模型：请打开左下角「设置 → 模型服务」，设置全局默认或场景绑定后再试");
        }
        return new SceneModel(global.getDefaultProviderKey(), global.getDefaultModel());
    }

    /** 全局默认模型（JD 解析、简历对比等未列入场景枚举的调用使用）。 */
    public SceneModel resolveGlobal() {
        LlmGlobalSetting global = globalMapper.selectById(1);
        if (global == null || blank(global.getDefaultProviderKey()) || blank(global.getDefaultModel())) {
            throw BizException.of(ErrorCode.BAD_REQUEST,
                    "尚未设置默认模型：请打开左下角「设置 → 模型服务」，将某个模型设为全局默认后再试");
        }
        return new SceneModel(global.getDefaultProviderKey(), global.getDefaultModel());
    }

    private SceneBindingResponse binding(LlmScene scene, LlmSceneSetting setting, LlmGlobalSetting global) {
        boolean inherited = setting == null;
        String effectiveProvider = inherited
                ? (global == null ? null : global.getDefaultProviderKey())
                : setting.getProviderKey();
        String effectiveModel = inherited
                ? (global == null ? null : global.getDefaultModel())
                : setting.getModel();
        return new SceneBindingResponse(scene, scene.label(),
                inherited ? null : setting.getProviderKey(),
                inherited ? null : setting.getModel(),
                inherited, effectiveProvider, effectiveModel,
                inherited ? null : setting.getUpdatedAt());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record SceneModel(String providerKey, String model) {
    }
}
