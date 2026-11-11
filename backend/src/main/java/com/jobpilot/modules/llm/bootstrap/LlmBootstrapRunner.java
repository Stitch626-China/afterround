package com.jobpilot.modules.llm.bootstrap;

import com.jobpilot.infrastructure.security.AesGcmCipher;
import com.jobpilot.modules.llm.entity.LlmModel;
import com.jobpilot.modules.llm.entity.LlmProvider;
import com.jobpilot.modules.llm.mapper.LlmModelMapper;
import com.jobpilot.modules.llm.mapper.LlmProviderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 首次启动种子（FRX.1）：仅当 Provider 表为空且 `.env` 提供 LLM_BOOTSTRAP_* 时导入一次。
 *
 * <p>日常配置请走页面/接口，不再依赖环境变量。
 */
@Component
public class LlmBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LlmBootstrapRunner.class);

    private final LlmProviderMapper providerMapper;
    private final LlmModelMapper modelMapper;
    private final AesGcmCipher cipher;

    @Value("${app.llm.bootstrap.provider-key:}")
    private String providerKey;
    @Value("${app.llm.bootstrap.label:}")
    private String label;
    @Value("${app.llm.bootstrap.base-url:}")
    private String baseUrl;
    @Value("${app.llm.bootstrap.api-key:}")
    private String apiKey;
    @Value("${app.llm.bootstrap.model:}")
    private String model;

    public LlmBootstrapRunner(LlmProviderMapper providerMapper, LlmModelMapper modelMapper, AesGcmCipher cipher) {
        this.providerMapper = providerMapper;
        this.modelMapper = modelMapper;
        this.cipher = cipher;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (providerKey.isBlank() || baseUrl.isBlank() || apiKey.isBlank()
                || providerMapper.selectCount(null) > 0) {
            return;
        }
        try {
            LlmProvider provider = new LlmProvider();
            provider.setUserId(1L);
            provider.setProviderKey(providerKey);
            provider.setLabel(label.isBlank() ? providerKey : label);
            provider.setBaseUrl(baseUrl);
            provider.setApiKeyCipher(cipher.encrypt(apiKey));
            provider.setEnabled(true);
            provider.setSortOrder(10);
            providerMapper.insert(provider);
            if (!model.isBlank()) {
                LlmModel entity = new LlmModel();
                entity.setUserId(1L);
                entity.setProviderId(provider.getId());
                entity.setModelName(model);
                entity.setLabel(model);
                entity.setEnabled(true);
                entity.setIsDefault(true);
                entity.setSortOrder(10);
                modelMapper.insert(entity);
            }
            log.info("已从环境变量导入 Provider 种子: {}", providerKey);
        } catch (Exception ex) {
            log.warn("Provider 种子导入失败，可稍后在模型设置页手工配置", ex);
        }
    }
}
