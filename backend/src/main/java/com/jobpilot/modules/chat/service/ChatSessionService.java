package com.jobpilot.modules.chat.service;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.api.PageResult;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.modules.chat.dto.ChatSessionResponse;
import com.jobpilot.modules.chat.dto.CreateChatSessionRequest;
import com.jobpilot.modules.chat.dto.SwitchModelRequest;
import com.jobpilot.modules.chat.entity.ChatSession;
import com.jobpilot.modules.chat.mapper.ChatSessionMapper;
import com.jobpilot.modules.llm.entity.LlmGlobalSetting;
import com.jobpilot.modules.llm.entity.LlmModel;
import com.jobpilot.modules.llm.entity.LlmProvider;
import com.jobpilot.modules.llm.mapper.LlmGlobalSettingMapper;
import com.jobpilot.modules.llm.mapper.LlmModelMapper;
import com.jobpilot.modules.llm.mapper.LlmProviderMapper;
import org.springframework.stereotype.Service;

/**
 * 会话管理：创建 / 查询 / 删除 / 两级切换。
 *
 * <p>切换守卫（FRX.6）：PROCESSING → 40901，LOCKED → 40902，目标模型不属于目标 Provider → 400。
 */
@Service
public class ChatSessionService {

    private final ChatSessionMapper sessionMapper;
    private final LlmProviderMapper providerMapper;
    private final LlmModelMapper modelMapper;
    private final LlmGlobalSettingMapper globalMapper;

    public ChatSessionService(ChatSessionMapper sessionMapper, LlmProviderMapper providerMapper,
                              LlmModelMapper modelMapper, LlmGlobalSettingMapper globalMapper) {
        this.sessionMapper = sessionMapper;
        this.providerMapper = providerMapper;
        this.modelMapper = modelMapper;
        this.globalMapper = globalMapper;
    }

    public PageResult<ChatSessionResponse> page(Integer page, Integer size) {
        int current = PageResult.normalizePage(page);
        int pageSize = PageResult.normalizeSize(size);
        Page<ChatSession> result = sessionMapper.selectPage(new Page<>(current, pageSize),
                Wrappers.<ChatSession>lambdaQuery()
                        .orderByDesc(ChatSession::getCreatedAt).orderByDesc(ChatSession::getId));
        return PageResult.of(result.getRecords().stream().map(ChatSessionResponse::from).toList(),
                result.getTotal(), current, pageSize);
    }

    public ChatSessionResponse create(CreateChatSessionRequest request) {
        LlmGlobalSetting global = globalMapper.selectById(1);
        String providerKey = firstNonBlank(request.providerKey(), global == null ? null : global.getDefaultProviderKey());
        String model = firstNonBlank(request.model(), global == null ? null : global.getDefaultModel());
        requireEnabledModel(providerKey, model);
        ChatSession entity = new ChatSession();
        entity.setUserId(1L);
        entity.setTitle(request.title() == null || request.title().isBlank() ? "新会话" : request.title());
        entity.setCurrentProviderKey(providerKey);
        entity.setCurrentModel(model);
        entity.setStatus("IDLE");
        sessionMapper.insert(entity);
        return ChatSessionResponse.from(sessionMapper.selectById(entity.getId()));
    }

    public ChatSessionResponse get(Long sessionId) {
        return ChatSessionResponse.from(require(sessionId));
    }

    public void delete(Long sessionId) {
        require(sessionId);
        sessionMapper.deleteById(sessionId);
    }

    public ChatSessionResponse switchModel(Long sessionId, SwitchModelRequest request) {
        ChatSession entity = require(sessionId);
        if ("PROCESSING".equals(entity.getStatus())) {
            throw BizException.of(ErrorCode.SESSION_SWITCH_FORBIDDEN);
        }
        if ("LOCKED".equals(entity.getStatus())) {
            throw BizException.of(ErrorCode.SESSION_LOCKED);
        }
        requireEnabledModel(request.providerKey(), request.model());
        // 条件更新保证「检查状态 + 写入绑定」原子：避免与发送消息的 PROCESSING 抢占竞态，
        // 也避免整行回写把并发抢占的 PROCESSING 覆盖回 IDLE。
        int updated = sessionMapper.update(null, Wrappers.<ChatSession>lambdaUpdate()
                .eq(ChatSession::getId, sessionId)
                .eq(ChatSession::getStatus, "IDLE")
                .set(ChatSession::getCurrentProviderKey, request.providerKey())
                .set(ChatSession::getCurrentModel, request.model())
                .set(ChatSession::getUpdatedAt, OffsetDateTime.now()));
        if (updated == 0) {
            ChatSession latest = require(sessionId);
            throw BizException.of("LOCKED".equals(latest.getStatus())
                    ? ErrorCode.SESSION_LOCKED : ErrorCode.SESSION_SWITCH_FORBIDDEN);
        }
        return ChatSessionResponse.from(require(sessionId));
    }

    public ChatSession require(Long sessionId) {
        ChatSession entity = sessionMapper.selectById(sessionId);
        if (entity == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "会话不存在: " + sessionId);
        }
        return entity;
    }

    private void requireEnabledModel(String providerKey, String model) {
        if (providerKey == null || providerKey.isBlank() || model == null || model.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "未指定 Provider/Model，且未配置全局默认");
        }
        LlmProvider provider = providerMapper.selectOne(Wrappers.<LlmProvider>lambdaQuery()
                .eq(LlmProvider::getProviderKey, providerKey));
        if (provider == null || !Boolean.TRUE.equals(provider.getEnabled())) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "Provider 不存在或已禁用: " + providerKey);
        }
        Long matched = modelMapper.selectCount(Wrappers.<LlmModel>lambdaQuery()
                .eq(LlmModel::getProviderId, provider.getId())
                .eq(LlmModel::getModelName, model)
                .eq(LlmModel::getEnabled, true));
        if (matched == 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "模型不存在或已禁用，或不属于该 Provider: " + model);
        }
    }

    private static String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
