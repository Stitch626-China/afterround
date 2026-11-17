package com.jobpilot.modules.llm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobpilot.LambdaCacheSupport;
import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.modules.chat.entity.ChatSession;
import com.jobpilot.modules.chat.mapper.ChatSessionMapper;
import com.jobpilot.modules.llm.dto.CreateModelRequest;
import com.jobpilot.modules.llm.dto.UpdateModelRequest;
import com.jobpilot.modules.llm.entity.LlmGlobalSetting;
import com.jobpilot.modules.llm.entity.LlmModel;
import com.jobpilot.modules.llm.entity.LlmProvider;
import com.jobpilot.modules.llm.entity.LlmSceneSetting;
import com.jobpilot.modules.llm.mapper.LlmGlobalSettingMapper;
import com.jobpilot.modules.llm.mapper.LlmModelMapper;
import com.jobpilot.modules.llm.mapper.LlmProviderMapper;
import com.jobpilot.modules.llm.mapper.LlmSceneSettingMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 模型 CRUD：同 Provider 唯一、默认模型互斥、被全局默认或会话引用时禁止删除。 */
@ExtendWith(MockitoExtension.class)
class LlmModelServiceTest {

    @Mock
    private LlmProviderMapper providerMapper;
    @Mock
    private LlmModelMapper modelMapper;
    @Mock
    private LlmGlobalSettingMapper globalMapper;
    @Mock
    private ChatSessionMapper sessionMapper;
    @Mock
    private LlmSceneSettingMapper sceneMapper;

    @BeforeAll
    static void initLambdaCache() {
        LambdaCacheSupport.init(LlmProvider.class, LlmModel.class, ChatSession.class, LlmSceneSetting.class);
    }

    private LlmModelService service() {
        return new LlmModelService(providerMapper, modelMapper, globalMapper, sessionMapper, sceneMapper);
    }

    private static LlmProvider provider() {
        LlmProvider entity = new LlmProvider();
        entity.setId(10L);
        entity.setProviderKey("p");
        return entity;
    }

    private static LlmModel model() {
        LlmModel entity = new LlmModel();
        entity.setId(20L);
        entity.setProviderId(10L);
        entity.setModelName("m");
        entity.setLabel("旧名字");
        entity.setEnabled(true);
        entity.setIsDefault(false);
        entity.setSortOrder(10);
        return entity;
    }

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo(expected));
    }

    @Test
    void createRejectsDuplicateAndClearsOldDefault() {
        when(providerMapper.selectOne(any())).thenReturn(provider());
        when(modelMapper.selectCount(any())).thenReturn(1L);
        assertCode(() -> service().create("p", new CreateModelRequest("m", "M", true, false, 10)),
                ErrorCode.MODEL_EXISTS);

        when(modelMapper.selectCount(any())).thenReturn(0L);
        var response = service().create("p", new CreateModelRequest("m", "M", null, true, null));
        assertThat(response.modelName()).isEqualTo("m");
        assertThat(response.isDefault()).isTrue();
        // 设为默认时先清空同 Provider 旧默认
        verify(modelMapper).update(isNull(), any());
    }

    @Test
    void createRejectsMissingProvider() {
        when(providerMapper.selectOne(any())).thenReturn(null);

        assertCode(() -> service().create("p", new CreateModelRequest("m", "M", true, false, 10)),
                ErrorCode.NOT_FOUND);
    }

    @Test
    void updateAppliesPartialChangesAndRequiresExistingModel() {
        when(providerMapper.selectOne(any())).thenReturn(provider());
        when(modelMapper.selectOne(any())).thenReturn(model());

        var response = service().update("p", "m", new UpdateModelRequest("新名字", false, true, 30));
        assertThat(response.label()).isEqualTo("新名字");
        assertThat(response.enabled()).isFalse();
        assertThat(response.isDefault()).isTrue();
        verify(modelMapper).updateById(any(LlmModel.class));
        verify(modelMapper).update(isNull(), any());

        when(modelMapper.selectOne(any())).thenReturn(null);
        assertCode(() -> service().update("p", "x", new UpdateModelRequest(null, null, null, null)),
                ErrorCode.NOT_FOUND);
    }

    @Test
    void deleteRejectsGlobalDefaultAndReferencedModel() {
        when(providerMapper.selectOne(any())).thenReturn(provider());
        when(modelMapper.selectOne(any())).thenReturn(model());
        LlmGlobalSetting global = new LlmGlobalSetting();
        global.setDefaultProviderKey("p");
        global.setDefaultModel("m");
        when(globalMapper.selectById(1)).thenReturn(global);
        assertCode(() -> service().delete("p", "m"), ErrorCode.RESOURCE_REFERENCED);

        when(globalMapper.selectById(1)).thenReturn(null);
        when(sessionMapper.selectCount(any())).thenReturn(1L);
        assertCode(() -> service().delete("p", "m"), ErrorCode.RESOURCE_REFERENCED);
    }

    @Test
    void deleteRemovesModelWhenUnreferenced() {
        when(providerMapper.selectOne(any())).thenReturn(provider());
        when(modelMapper.selectOne(any())).thenReturn(model());
        when(globalMapper.selectById(1)).thenReturn(null);
        when(sessionMapper.selectCount(any())).thenReturn(0L);

        service().delete("p", "m");

        verify(modelMapper).deleteById(20L);
        verify(modelMapper, never()).update(isNull(), any());
    }
}
