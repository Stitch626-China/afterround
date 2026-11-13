package com.jobpilot.modules.llm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.jobpilot.LambdaCacheSupport;
import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.infrastructure.llm.ProviderClientRegistry;
import com.jobpilot.infrastructure.security.AesGcmCipher;
import com.jobpilot.modules.chat.entity.ChatSession;
import com.jobpilot.modules.chat.mapper.ChatSessionMapper;
import com.jobpilot.modules.llm.dto.CreateProviderRequest;
import com.jobpilot.modules.llm.dto.UpdateProviderRequest;
import com.jobpilot.modules.llm.entity.LlmGlobalSetting;
import com.jobpilot.modules.llm.entity.LlmModel;
import com.jobpilot.modules.llm.entity.LlmProvider;
import com.jobpilot.modules.llm.mapper.LlmGlobalSettingMapper;
import com.jobpilot.modules.llm.mapper.LlmModelMapper;
import com.jobpilot.modules.llm.mapper.LlmProviderMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Provider CRUD：密钥只加密落库、连接信息变更才失效客户端、被引用时禁止删除。 */
@ExtendWith(MockitoExtension.class)
class LlmProviderServiceTest {

    @Mock
    private LlmProviderMapper providerMapper;
    @Mock
    private LlmModelMapper modelMapper;
    @Mock
    private LlmGlobalSettingMapper globalMapper;
    @Mock
    private ChatSessionMapper sessionMapper;
    @Mock
    private AesGcmCipher cipher;
    @Mock
    private ProviderClientRegistry registry;

    @BeforeAll
    static void initLambdaCache() {
        LambdaCacheSupport.init(LlmProvider.class, LlmModel.class, ChatSession.class);
    }

    private LlmProviderService service() {
        return new LlmProviderService(providerMapper, modelMapper, globalMapper, sessionMapper, cipher, registry);
    }

    private static LlmProvider provider() {
        LlmProvider entity = new LlmProvider();
        entity.setId(10L);
        entity.setProviderKey("p");
        entity.setLabel("内网");
        entity.setBaseUrl("http://localhost:3000/v1");
        entity.setApiKeyCipher("cipher");
        entity.setEnabled(true);
        entity.setSortOrder(10);
        return entity;
    }

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo(expected));
    }

    @Test
    void listAndGetMaskSecrets() {
        when(providerMapper.selectList(any())).thenReturn(List.of(provider()));
        when(providerMapper.selectOne(any())).thenReturn(provider());
        when(modelMapper.selectList(any())).thenReturn(List.of(new LlmModel()));
        when(cipher.decrypt("cipher")).thenReturn("sk-abcdefgh1234");

        assertThat(service().list()).hasSize(1);
        assertThat(service().get("p").apiKeyMasked()).isNotEqualTo("cipher");

        when(cipher.decrypt("cipher")).thenThrow(new IllegalStateException("bad key"));
        assertThat(service().get("p").apiKeyMasked()).isEqualTo("****");

        when(providerMapper.selectOne(any())).thenReturn(null);
        assertCode(() -> service().get("missing"), ErrorCode.NOT_FOUND);
    }

    @Test
    void createRejectsDuplicateAndEncryptsKey() {
        when(providerMapper.selectCount(any())).thenReturn(1L);
        assertCode(() -> service().create(new CreateProviderRequest(
                "p", "内网", "http://localhost:3000/v1", "sk-1", true, 10)), ErrorCode.PROVIDER_EXISTS);

        when(providerMapper.selectCount(any())).thenReturn(0L);
        when(cipher.encrypt("sk-1")).thenReturn("cipher");
        when(modelMapper.selectList(any())).thenReturn(List.of());
        var response = service().create(new CreateProviderRequest(
                "p", "内网", "http://localhost:3000/v1", "sk-1", null, null));
        assertThat(response.providerKey()).isEqualTo("p");
        verify(providerMapper).insert(any(LlmProvider.class));
    }

    @Test
    void updateInvalidatesClientOnlyWhenConnectionChanges() {
        when(providerMapper.selectOne(any())).thenReturn(provider());
        when(modelMapper.selectList(any())).thenReturn(List.of());

        service().update("p", new UpdateProviderRequest("新名字", null, null, false, null));
        verify(registry, never()).invalidate(any());

        when(cipher.encrypt("sk-new")).thenReturn("cipher2");
        service().update("p", new UpdateProviderRequest(null, "http://other:3000/v1", "sk-new", null, null));
        verify(registry).invalidate("p");
    }

    @Test
    void deleteRejectsGlobalDefaultAndReferencedProvider() {
        when(providerMapper.selectOne(any())).thenReturn(provider());
        LlmGlobalSetting global = new LlmGlobalSetting();
        global.setDefaultProviderKey("p");
        when(globalMapper.selectById(1)).thenReturn(global);
        assertCode(() -> service().delete("p"), ErrorCode.RESOURCE_REFERENCED);

        when(globalMapper.selectById(1)).thenReturn(null);
        when(sessionMapper.selectCount(any())).thenReturn(1L);
        assertCode(() -> service().delete("p"), ErrorCode.RESOURCE_REFERENCED);
    }

    @Test
    void deleteRemovesProviderAndInvalidatesClient() {
        when(providerMapper.selectOne(any())).thenReturn(provider());
        when(globalMapper.selectById(1)).thenReturn(null);
        when(sessionMapper.selectCount(any())).thenReturn(0L);

        service().delete("p");

        verify(providerMapper).deleteById(10L);
        verify(registry).invalidate("p");
    }
}
