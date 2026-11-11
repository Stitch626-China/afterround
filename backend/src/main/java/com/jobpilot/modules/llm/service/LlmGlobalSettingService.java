package com.jobpilot.modules.llm.service;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.modules.llm.dto.GlobalSettingResponse;
import com.jobpilot.modules.llm.dto.UpdateGlobalSettingRequest;
import com.jobpilot.modules.llm.entity.LlmGlobalSetting;
import com.jobpilot.modules.llm.entity.LlmModel;
import com.jobpilot.modules.llm.entity.LlmProvider;
import com.jobpilot.modules.llm.mapper.LlmGlobalSettingMapper;
import com.jobpilot.modules.llm.mapper.LlmModelMapper;
import com.jobpilot.modules.llm.mapper.LlmProviderMapper;
import org.springframework.stereotype.Service;

/** 全局默认 Provider + Model（单行表，id 固定 1）。 */
@Service
public class LlmGlobalSettingService {

    private final LlmGlobalSettingMapper globalMapper;
    private final LlmProviderMapper providerMapper;
    private final LlmModelMapper modelMapper;

    public LlmGlobalSettingService(LlmGlobalSettingMapper globalMapper, LlmProviderMapper providerMapper,
                                   LlmModelMapper modelMapper) {
        this.globalMapper = globalMapper;
        this.providerMapper = providerMapper;
        this.modelMapper = modelMapper;
    }

    public GlobalSettingResponse get() {
        LlmGlobalSetting entity = globalMapper.selectById(1);
        return entity == null ? new GlobalSettingResponse(null, null)
                : new GlobalSettingResponse(entity.getDefaultProviderKey(), entity.getDefaultModel());
    }

    /** 设置默认值；模型必须真实属于该 Provider，否则 400。 */
    public GlobalSettingResponse update(UpdateGlobalSettingRequest request) {
        LlmProvider provider = providerMapper.selectOne(Wrappers.<LlmProvider>lambdaQuery()
                .eq(LlmProvider::getProviderKey, request.defaultProviderKey()));
        if (provider == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "Provider 不存在: " + request.defaultProviderKey());
        }
        Long matched = modelMapper.selectCount(Wrappers.<LlmModel>lambdaQuery()
                .eq(LlmModel::getProviderId, provider.getId())
                .eq(LlmModel::getModelName, request.defaultModel()));
        if (matched == 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "模型不属于该 Provider: " + request.defaultModel());
        }
        LlmGlobalSetting entity = globalMapper.selectById(1);
        if (entity == null) {
            entity = new LlmGlobalSetting();
            entity.setId(1);
            entity.setUserId(1L);
            entity.setDefaultProviderKey(request.defaultProviderKey());
            entity.setDefaultModel(request.defaultModel());
            globalMapper.insert(entity);
        } else {
            entity.setDefaultProviderKey(request.defaultProviderKey());
            entity.setDefaultModel(request.defaultModel());
            entity.setUpdatedAt(OffsetDateTime.now());
            globalMapper.updateById(entity);
        }
        return new GlobalSettingResponse(entity.getDefaultProviderKey(), entity.getDefaultModel());
    }
}
