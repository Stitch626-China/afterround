package com.jobpilot.infrastructure.llm;

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
import org.springframework.stereotype.Component;

/**
 * Provider 客户端注册中心（§6.2）：按 providerKey 缓存客户端，CRUD 时精确失效实现热更新。
 *
 * <p>Model 不参与客户端构建，只随请求体传入，因此新增模型不需要重建客户端。
 */
@Component
public class ProviderClientRegistry {

    private final Map<String, LlmClient> cache = new ConcurrentHashMap<>();
    private final LlmProviderMapper providerMapper;
    private final AesGcmCipher cipher;

    public ProviderClientRegistry(LlmProviderMapper providerMapper, AesGcmCipher cipher) {
        this.providerMapper = providerMapper;
        this.cipher = cipher;
    }

    /** 获取客户端；Provider 不存在或已禁用时抛出业务异常。 */
    public LlmClient get(String providerKey) {
        return cache.computeIfAbsent(providerKey, key -> {
            LlmProvider provider = providerMapper.selectOne(
                    Wrappers.<LlmProvider>lambdaQuery().eq(LlmProvider::getProviderKey, key));
            if (provider == null) {
                throw BizException.of(ErrorCode.NOT_FOUND, "Provider 不存在: " + key);
            }
            if (!Boolean.TRUE.equals(provider.getEnabled())) {
                throw BizException.of(ErrorCode.BAD_REQUEST, "Provider 已禁用: " + key);
            }
            return new OpenAiCompatibleClient(provider.getBaseUrl(), cipher.decrypt(provider.getApiKeyCipher()));
        });
    }

    /** 精确失效：Provider 更新或删除后调用。 */
    public void invalidate(String providerKey) {
        cache.remove(providerKey);
    }
}
