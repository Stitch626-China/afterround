package com.jobpilot.modules.llm.service;

import java.time.OffsetDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.infrastructure.llm.ProviderClientRegistry;
import com.jobpilot.infrastructure.security.AesGcmCipher;
import com.jobpilot.modules.chat.entity.ChatSession;
import com.jobpilot.modules.chat.mapper.ChatSessionMapper;
import com.jobpilot.modules.eval.service.EvalClientFactory;
import com.jobpilot.modules.llm.dto.CreateProviderRequest;
import com.jobpilot.modules.llm.dto.LlmProviderResponse;
import com.jobpilot.modules.llm.dto.UpdateProviderRequest;
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
 * Provider 管理（FRX.1）：数据库唯一来源，CRUD 后精确失效客户端缓存实现热更新。
 */
@Service
public class LlmProviderService {

    private final LlmProviderMapper providerMapper;
    private final LlmModelMapper modelMapper;
    private final LlmGlobalSettingMapper globalMapper;
    private final ChatSessionMapper sessionMapper;
    private final LlmSceneSettingMapper sceneMapper;
    private final AesGcmCipher cipher;
    private final ProviderClientRegistry registry;
    private final EvalClientFactory evalClientFactory;

    public LlmProviderService(LlmProviderMapper providerMapper, LlmModelMapper modelMapper,
                              LlmGlobalSettingMapper globalMapper, ChatSessionMapper sessionMapper,
                              LlmSceneSettingMapper sceneMapper,
                              AesGcmCipher cipher, ProviderClientRegistry registry,
                              EvalClientFactory evalClientFactory) {
        this.providerMapper = providerMapper;
        this.modelMapper = modelMapper;
        this.globalMapper = globalMapper;
        this.sessionMapper = sessionMapper;
        this.sceneMapper = sceneMapper;
        this.cipher = cipher;
        this.registry = registry;
        this.evalClientFactory = evalClientFactory;
    }

    /** 全部 Provider 及其模型，按 sortOrder 排序。 */
    public List<LlmProviderResponse> list() {
        return providerMapper.selectList(Wrappers.<LlmProvider>lambdaQuery()
                        .orderByAsc(LlmProvider::getSortOrder).orderByAsc(LlmProvider::getId))
                .stream().map(this::toResponse).toList();
    }

    public LlmProviderResponse get(String providerKey) {
        return toResponse(require(providerKey));
    }

    public LlmProviderResponse create(CreateProviderRequest request) {
        Long exists = providerMapper.selectCount(Wrappers.<LlmProvider>lambdaQuery()
                .eq(LlmProvider::getProviderKey, request.providerKey()));
        if (exists > 0) {
            throw BizException.of(ErrorCode.PROVIDER_EXISTS);
        }
        LlmProvider entity = new LlmProvider();
        entity.setUserId(1L);
        entity.setProviderKey(request.providerKey());
        entity.setLabel(request.label());
        entity.setBaseUrl(request.baseUrl());
        entity.setApiKeyCipher(cipher.encrypt(request.apiKey()));
        entity.setEnabled(request.enabled() == null || request.enabled());
        entity.setSortOrder(request.sortOrder() == null ? 10 : request.sortOrder());
        providerMapper.insert(entity);
        return toResponse(entity);
    }

    public LlmProviderResponse update(String providerKey, UpdateProviderRequest request) {
        LlmProvider entity = require(providerKey);
        boolean changedConnection = false;
        if (request.label() != null) {
            entity.setLabel(request.label());
        }
        if (request.baseUrl() != null) {
            entity.setBaseUrl(request.baseUrl());
            changedConnection = true;
        }
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            entity.setApiKeyCipher(cipher.encrypt(request.apiKey()));
            changedConnection = true;
        }
        if (request.enabled() != null) {
            entity.setEnabled(request.enabled());
            // 启停影响可用性，必须失效缓存，否则禁用后已缓存客户端仍可继续调用
            changedConnection = true;
        }
        if (request.sortOrder() != null) {
            entity.setSortOrder(request.sortOrder());
        }
        entity.setUpdatedAt(OffsetDateTime.now());
        providerMapper.updateById(entity);
        if (changedConnection) {
            registry.invalidate(providerKey);
            evalClientFactory.invalidate(providerKey);
        }
        return toResponse(entity);
    }

    /** 删除 Provider（级联删除模型）；被全局默认或进行中的会话引用时拒绝。 */
    public void delete(String providerKey) {
        LlmProvider entity = require(providerKey);
        LlmGlobalSetting global = globalMapper.selectById(1);
        if (global != null && providerKey.equals(global.getDefaultProviderKey())) {
            throw BizException.of(ErrorCode.RESOURCE_REFERENCED, "该 Provider 是全局默认，不可删除");
        }
        Long referenced = sessionMapper.selectCount(Wrappers.<ChatSession>lambdaQuery()
                .eq(ChatSession::getCurrentProviderKey, providerKey)
                .in(ChatSession::getStatus, "IDLE", "PROCESSING", "LOCKED"));
        if (referenced > 0) {
            throw BizException.of(ErrorCode.RESOURCE_REFERENCED, "该 Provider 正被会话引用，不可删除");
        }
        providerMapper.deleteById(entity.getId());
        registry.invalidate(providerKey);
        evalClientFactory.invalidate(providerKey);
    }

    private LlmProvider require(String providerKey) {
        LlmProvider entity = providerMapper.selectOne(Wrappers.<LlmProvider>lambdaQuery()
                .eq(LlmProvider::getProviderKey, providerKey));
        if (entity == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "Provider 不存在: " + providerKey);
        }
        return entity;
    }

    private LlmProviderResponse toResponse(LlmProvider entity) {
        List<LlmModel> models = modelMapper.selectList(Wrappers.<LlmModel>lambdaQuery()
                .eq(LlmModel::getProviderId, entity.getId())
                .orderByAsc(LlmModel::getSortOrder).orderByAsc(LlmModel::getId));
        return new LlmProviderResponse(entity.getProviderKey(), entity.getLabel(), entity.getBaseUrl(),
                masked(entity.getApiKeyCipher()), entity.getEnabled(), entity.getSortOrder(),
                models.stream().map(com.jobpilot.modules.llm.dto.LlmModelResponse::from).toList(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    /** 解密后掩码；主密钥变更导致解密失败时只显示 ****，不影响列表可用。 */
    private String masked(String cipherText) {
        try {
            return AesGcmCipher.mask(cipher.decrypt(cipherText));
        } catch (Exception ex) {
            return "****";
        }
    }
}
