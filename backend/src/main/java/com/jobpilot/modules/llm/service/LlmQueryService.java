package com.jobpilot.modules.llm.service;

import java.util.List;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.common.llm.ChatMessage;
import com.jobpilot.common.llm.ChatResult;
import com.jobpilot.common.llm.LlmClient;
import com.jobpilot.infrastructure.llm.LlmCallLogger;
import com.jobpilot.infrastructure.llm.ProviderClientRegistry;
import com.jobpilot.modules.llm.dto.ConnectivityTestResponse;
import com.jobpilot.modules.llm.dto.TestProviderRequest;
import com.jobpilot.modules.llm.dto.UpstreamModelListResponse;
import com.jobpilot.modules.llm.entity.LlmModel;
import com.jobpilot.modules.llm.entity.LlmProvider;
import com.jobpilot.modules.llm.mapper.LlmModelMapper;
import com.jobpilot.modules.llm.mapper.LlmProviderMapper;
import org.springframework.stereotype.Service;

/**
 * 连通性测试（FRX.3）与上游模型列举（FRX.7）。
 */
@Service
public class LlmQueryService {

    private static final String TEST_PROMPT = "请只回复 ok";

    private final LlmProviderMapper providerMapper;
    private final LlmModelMapper modelMapper;
    private final ProviderClientRegistry registry;
    private final LlmCallLogger callLogger;

    public LlmQueryService(LlmProviderMapper providerMapper, LlmModelMapper modelMapper,
                           ProviderClientRegistry registry, LlmCallLogger callLogger) {
        this.providerMapper = providerMapper;
        this.modelMapper = modelMapper;
        this.registry = registry;
        this.callLogger = callLogger;
    }

    /** 发起一次最小调用；无论成败都返回 200，由 success 字段区分，并回显上游真实 model。 */
    public ConnectivityTestResponse test(String providerKey, TestProviderRequest request) {
        requireProvider(providerKey);
        String model = resolveModel(providerKey, request == null ? null : request.modelName());
        LlmClient client = registry.get(providerKey);
        long start = System.nanoTime();
        try {
            ChatResult result = client.chat(model, List.of(ChatMessage.user(TEST_PROMPT)));
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            callLogger.log("CONNECTIVITY_TEST", providerKey, model, result.promptTokens(),
                    result.completionTokens(), result.totalTokens(), elapsed, true, null);
            return new ConnectivityTestResponse(true, elapsed, null, result.model());
        } catch (Exception ex) {
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            callLogger.log("CONNECTIVITY_TEST", providerKey, model, null, null, null, elapsed, false, ex.getMessage());
            return new ConnectivityTestResponse(false, elapsed, ex.getMessage(), null);
        }
    }

    /** 优先实时拉取上游 /models，失败时回退库内登记列表并标注来源。 */
    public UpstreamModelListResponse listUpstreamModels(String providerKey) {
        LlmProvider provider = requireProvider(providerKey);
        try {
            List<String> models = registry.get(providerKey).listModels();
            return new UpstreamModelListResponse(UpstreamModelListResponse.UPSTREAM, models);
        } catch (Exception ex) {
            List<String> local = modelMapper.selectList(Wrappers.<LlmModel>lambdaQuery()
                            .eq(LlmModel::getProviderId, provider.getId())
                            .orderByAsc(LlmModel::getSortOrder).orderByAsc(LlmModel::getId))
                    .stream().map(LlmModel::getModelName).toList();
            return new UpstreamModelListResponse(UpstreamModelListResponse.LOCAL_FALLBACK, local);
        }
    }

    private LlmProvider requireProvider(String providerKey) {
        LlmProvider provider = providerMapper.selectOne(Wrappers.<LlmProvider>lambdaQuery()
                .eq(LlmProvider::getProviderKey, providerKey));
        if (provider == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "Provider 不存在: " + providerKey);
        }
        return provider;
    }

    /** 未指定时取该 Provider 的默认模型（isDefault 优先，其次 sortOrder 最小）。 */
    private String resolveModel(String providerKey, String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        LlmProvider provider = requireProvider(providerKey);
        LlmModel model = modelMapper.selectOne(Wrappers.<LlmModel>lambdaQuery()
                .eq(LlmModel::getProviderId, provider.getId())
                .eq(LlmModel::getEnabled, true)
                .orderByDesc(LlmModel::getIsDefault)
                .orderByAsc(LlmModel::getSortOrder)
                .orderByAsc(LlmModel::getId)
                .last("LIMIT 1"));
        if (model == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "该 Provider 下没有可用模型");
        }
        return model.getModelName();
    }
}
