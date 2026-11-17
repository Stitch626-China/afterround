package com.jobpilot.modules.llm.service;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.modules.chat.entity.ChatSession;
import com.jobpilot.modules.chat.mapper.ChatSessionMapper;
import com.jobpilot.modules.llm.dto.CreateModelRequest;
import com.jobpilot.modules.llm.dto.LlmModelResponse;
import com.jobpilot.modules.llm.dto.UpdateModelRequest;
import com.jobpilot.modules.llm.entity.LlmGlobalSetting;
import com.jobpilot.modules.llm.entity.LlmModel;
import com.jobpilot.modules.llm.entity.LlmProvider;
import com.jobpilot.modules.llm.entity.LlmSceneSetting;
import com.jobpilot.modules.llm.mapper.LlmGlobalSettingMapper;
import com.jobpilot.modules.llm.mapper.LlmModelMapper;
import com.jobpilot.modules.llm.mapper.LlmProviderMapper;
import com.jobpilot.modules.llm.mapper.LlmSceneSettingMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provider 下的模型管理；同一 Provider 内 modelName 唯一，默认模型至多一个。
 */
@Service
public class LlmModelService {

    private final LlmProviderMapper providerMapper;
    private final LlmModelMapper modelMapper;
    private final LlmGlobalSettingMapper globalMapper;
    private final ChatSessionMapper sessionMapper;
    private final LlmSceneSettingMapper sceneMapper;

    public LlmModelService(LlmProviderMapper providerMapper, LlmModelMapper modelMapper,
                           LlmGlobalSettingMapper globalMapper, ChatSessionMapper sessionMapper,
                           LlmSceneSettingMapper sceneMapper) {
        this.providerMapper = providerMapper;
        this.modelMapper = modelMapper;
        this.globalMapper = globalMapper;
        this.sessionMapper = sessionMapper;
        this.sceneMapper = sceneMapper;
    }

    @Transactional
    public LlmModelResponse create(String providerKey, CreateModelRequest request) {
        LlmProvider provider = requireProvider(providerKey);
        Long exists = modelMapper.selectCount(Wrappers.<LlmModel>lambdaQuery()
                .eq(LlmModel::getProviderId, provider.getId())
                .eq(LlmModel::getModelName, request.modelName()));
        if (exists > 0) {
            throw BizException.of(ErrorCode.MODEL_EXISTS);
        }
        LlmModel entity = new LlmModel();
        entity.setUserId(1L);
        entity.setProviderId(provider.getId());
        entity.setModelName(request.modelName());
        entity.setLabel(request.label());
        entity.setEnabled(request.enabled() == null || request.enabled());
        entity.setIsDefault(Boolean.TRUE.equals(request.isDefault()));
        entity.setSortOrder(request.sortOrder() == null ? 10 : request.sortOrder());
        if (entity.getIsDefault()) {
            clearDefaults(provider.getId());
        }
        modelMapper.insert(entity);
        return LlmModelResponse.from(entity);
    }

    @Transactional
    public LlmModelResponse update(String providerKey, String modelName, UpdateModelRequest request) {
        LlmProvider provider = requireProvider(providerKey);
        LlmModel entity = requireModel(provider.getId(), modelName);
        if (request.label() != null) {
            entity.setLabel(request.label());
        }
        if (request.enabled() != null) {
            entity.setEnabled(request.enabled());
        }
        if (request.sortOrder() != null) {
            entity.setSortOrder(request.sortOrder());
        }
        if (request.isDefault() != null) {
            entity.setIsDefault(request.isDefault());
            if (request.isDefault()) {
                clearDefaults(provider.getId());
            }
        }
        entity.setUpdatedAt(OffsetDateTime.now());
        modelMapper.updateById(entity);
        return LlmModelResponse.from(entity);
    }

    /** 删除模型；被全局默认或进行中的会话引用时拒绝。 */
    public void delete(String providerKey, String modelName) {
        LlmProvider provider = requireProvider(providerKey);
        LlmModel entity = requireModel(provider.getId(), modelName);
        LlmGlobalSetting global = globalMapper.selectById(1);
        if (global != null && providerKey.equals(global.getDefaultProviderKey())
                && modelName.equals(global.getDefaultModel())) {
            throw BizException.of(ErrorCode.RESOURCE_REFERENCED, "该模型是全局默认，不可删除");
        }
        Long referenced = sessionMapper.selectCount(Wrappers.<ChatSession>lambdaQuery()
                .eq(ChatSession::getCurrentProviderKey, providerKey)
                .eq(ChatSession::getCurrentModel, modelName)
                .in(ChatSession::getStatus, "IDLE", "PROCESSING"));
        if (referenced > 0) {
            throw BizException.of(ErrorCode.RESOURCE_REFERENCED, "该模型正被会话引用，不可删除");
        }
        Long sceneRefs = sceneMapper.selectCount(Wrappers.<LlmSceneSetting>lambdaQuery()
                .eq(LlmSceneSetting::getProviderKey, providerKey)
                .eq(LlmSceneSetting::getModel, modelName));
        if (sceneRefs != null && sceneRefs > 0) {
            throw BizException.of(ErrorCode.RESOURCE_REFERENCED, "该模型正被场景绑定引用，不可删除");
        }
        modelMapper.deleteById(entity.getId());
    }

    private void clearDefaults(Long providerId) {
        modelMapper.update(null, Wrappers.<LlmModel>lambdaUpdate()
                .eq(LlmModel::getProviderId, providerId)
                .eq(LlmModel::getIsDefault, true)
                .set(LlmModel::getIsDefault, false));
    }

    private LlmProvider requireProvider(String providerKey) {
        LlmProvider provider = providerMapper.selectOne(Wrappers.<LlmProvider>lambdaQuery()
                .eq(LlmProvider::getProviderKey, providerKey));
        if (provider == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "Provider 不存在: " + providerKey);
        }
        return provider;
    }

    private LlmModel requireModel(Long providerId, String modelName) {
        LlmModel entity = modelMapper.selectOne(Wrappers.<LlmModel>lambdaQuery()
                .eq(LlmModel::getProviderId, providerId)
                .eq(LlmModel::getModelName, modelName));
        if (entity == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "模型不存在: " + modelName);
        }
        return entity;
    }
}
