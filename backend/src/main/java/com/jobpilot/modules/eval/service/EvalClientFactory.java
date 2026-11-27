package com.jobpilot.modules.eval.service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.common.llm.LlmClient;
import com.jobpilot.common.llm.OpenAiCompatibleClient;
import com.jobpilot.infrastructure.security.AesGcmCipher;
import com.jobpilot.modules.llm.entity.LlmProvider;
import com.jobpilot.modules.llm.mapper.LlmProviderMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 评测专用客户端工厂：使用 {@code app.eval.*} 的 token 上限与超时，
 * 不影响生产调用的 4096 / 120s；Provider 更新后与生产注册表各自失效。
 */
@Component
public class EvalClientFactory {

    private final LlmProviderMapper providerMapper;
    private final AesGcmCipher cipher;
    private final Map<String, LlmClient> cache = new ConcurrentHashMap<>();

    @Value("${app.eval.max-tokens:8192}")
    private int maxTokens = 8192;
    @Value("${app.eval.timeout-seconds:300}")
    private int timeoutSeconds = 300;

    public EvalClientFactory(LlmProviderMapper providerMapper, AesGcmCipher cipher) {
        this.providerMapper = providerMapper;
        this.cipher = cipher;
    }

    public LlmClient get(String providerKey) {
        return cache.computeIfAbsent(providerKey, this::build);
    }

    /** Provider 更新 / 删除后精确失效。 */
    public void invalidate(String providerKey) {
        cache.remove(providerKey);
    }

    private LlmClient build(String providerKey) {
        LlmProvider provider = providerMapper.selectOne(
                Wrappers.<LlmProvider>lambdaQuery().eq(LlmProvider::getProviderKey, providerKey));
        if (provider == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "Provider 不存在: " + providerKey);
        }
        if (!Boolean.TRUE.equals(provider.getEnabled())) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "Provider 已禁用: " + providerKey);
        }
        return new OpenAiCompatibleClient(provider.getBaseUrl(), cipher.decrypt(provider.getApiKeyCipher()),
                maxTokens, Duration.ofSeconds(timeoutSeconds));
    }
}
