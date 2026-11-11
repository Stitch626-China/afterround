package com.jobpilot.infrastructure.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.infrastructure.security.AesGcmCipher;
import com.jobpilot.modules.llm.entity.LlmProvider;
import com.jobpilot.modules.llm.mapper.LlmProviderMapper;
import org.junit.jupiter.api.Test;

class ProviderClientRegistryTest {

    private final LlmProviderMapper mapper = mock(LlmProviderMapper.class);
    private final AesGcmCipher cipher = new AesGcmCipher("unit-test-secret");
    private final ProviderClientRegistry registry = new ProviderClientRegistry(mapper, cipher);

    @Test
    void cachesPerProviderAndInvalidates() {
        when(mapper.selectOne(any())).thenReturn(provider(true));

        registry.get("p1");
        registry.get("p1");
        verify(mapper, times(1)).selectOne(any());

        registry.invalidate("p1");
        registry.get("p1");
        verify(mapper, times(2)).selectOne(any());
    }

    @Test
    void missingProviderFails() {
        when(mapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> registry.get("missing"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void disabledProviderIsRejected() {
        when(mapper.selectOne(any())).thenReturn(provider(false));

        assertThatThrownBy(() -> registry.get("p1"))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).errorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
    }

    private LlmProvider provider(boolean enabled) {
        LlmProvider provider = new LlmProvider();
        provider.setProviderKey("p1");
        provider.setLabel("p1");
        provider.setBaseUrl("http://127.0.0.1:9/v1");
        provider.setApiKeyCipher(cipher.encrypt("sk-test"));
        provider.setEnabled(enabled);
        return provider;
    }
}
