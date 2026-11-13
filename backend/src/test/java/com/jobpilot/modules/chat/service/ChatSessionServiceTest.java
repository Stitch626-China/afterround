package com.jobpilot.modules.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import java.util.List;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jobpilot.LambdaCacheSupport;
import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.exception.BizException;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 会话分页 / 创建 / 删除与两级切换守卫（含并发抢占失败分支）。 */
@ExtendWith(MockitoExtension.class)
class ChatSessionServiceTest {

    @Mock
    private ChatSessionMapper sessionMapper;
    @Mock
    private LlmProviderMapper providerMapper;
    @Mock
    private LlmModelMapper modelMapper;
    @Mock
    private LlmGlobalSettingMapper globalMapper;

    @BeforeAll
    static void initLambdaCache() {
        LambdaCacheSupport.init(ChatSession.class, LlmProvider.class, LlmModel.class);
    }

    private ChatSessionService service() {
        return new ChatSessionService(sessionMapper, providerMapper, modelMapper, globalMapper);
    }

    private static ChatSession session(Long id, String status) {
        ChatSession entity = new ChatSession();
        entity.setId(id);
        entity.setStatus(status);
        entity.setCurrentProviderKey("p");
        entity.setCurrentModel("m");
        return entity;
    }

    private static LlmProvider provider(boolean enabled) {
        LlmProvider entity = new LlmProvider();
        entity.setId(10L);
        entity.setProviderKey("p");
        entity.setEnabled(enabled);
        return entity;
    }

    /** 断言业务异常码。 */
    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo(expected));
    }

    @Test
    void pageMapsRecords() {
        Page<ChatSession> page = new Page<>(1, 20);
        page.setRecords(List.of(session(1L, "IDLE")));
        page.setTotal(1);
        when(sessionMapper.selectPage(any(), any())).thenReturn(page);

        assertThat(service().page(1, 20).items()).hasSize(1);
    }

    @Test
    void createFallsBackToGlobalAndDefaultTitle() {
        LlmGlobalSetting global = new LlmGlobalSetting();
        global.setDefaultProviderKey("p");
        global.setDefaultModel("m");
        when(globalMapper.selectById(1)).thenReturn(global);
        when(providerMapper.selectOne(any())).thenReturn(provider(true));
        when(modelMapper.selectCount(any())).thenReturn(1L);
        when(sessionMapper.selectById(any())).thenReturn(session(9L, "IDLE"));

        assertThat(service().create(new CreateChatSessionRequest(null, null, null)).id()).isEqualTo(9L);
    }

    @Test
    void createWithoutAnyModelIsBadRequest() {
        when(globalMapper.selectById(1)).thenReturn(null);

        assertCode(() -> service().create(new CreateChatSessionRequest(null, null, null)), ErrorCode.BAD_REQUEST);
    }

    @Test
    void getAndDeleteMissingSessionReturnNotFound() {
        when(sessionMapper.selectById(5L)).thenReturn(null);

        assertCode(() -> service().get(5L), ErrorCode.NOT_FOUND);
        assertCode(() -> service().delete(5L), ErrorCode.NOT_FOUND);
    }

    @Test
    void switchRejectsProcessingAndLocked() {
        when(sessionMapper.selectById(1L)).thenReturn(session(1L, "PROCESSING"));
        assertCode(() -> service().switchModel(1L, new SwitchModelRequest("p", "m")),
                ErrorCode.SESSION_SWITCH_FORBIDDEN);

        when(sessionMapper.selectById(2L)).thenReturn(session(2L, "LOCKED"));
        assertCode(() -> service().switchModel(2L, new SwitchModelRequest("p", "m")),
                ErrorCode.SESSION_LOCKED);
    }

    @Test
    void switchRejectsUnknownOrDisabledModel() {
        when(sessionMapper.selectById(1L)).thenReturn(session(1L, "IDLE"));
        assertCode(() -> service().switchModel(1L, new SwitchModelRequest(" ", "m")), ErrorCode.BAD_REQUEST);

        when(providerMapper.selectOne(any())).thenReturn(provider(false));
        assertCode(() -> service().switchModel(1L, new SwitchModelRequest("p", "m")), ErrorCode.BAD_REQUEST);

        when(providerMapper.selectOne(any())).thenReturn(provider(true));
        when(modelMapper.selectCount(any())).thenReturn(0L);
        assertCode(() -> service().switchModel(1L, new SwitchModelRequest("p", "m")), ErrorCode.BAD_REQUEST);
    }

    @Test
    void switchSucceedsWithAtomicUpdate() {
        when(sessionMapper.selectById(1L))
                .thenReturn(session(1L, "IDLE"), session(1L, "IDLE"));
        when(providerMapper.selectOne(any())).thenReturn(provider(true));
        when(modelMapper.selectCount(any())).thenReturn(1L);
        when(sessionMapper.update(isNull(), any())).thenReturn(1);

        assertThat(service().switchModel(1L, new SwitchModelRequest("p", "m")).status()).isEqualTo("IDLE");
    }

    @Test
    void switchLosesRaceToProcessingOrLocked() {
        when(sessionMapper.selectById(1L)).thenReturn(session(1L, "IDLE"), session(1L, "PROCESSING"));
        when(providerMapper.selectOne(any())).thenReturn(provider(true));
        when(modelMapper.selectCount(any())).thenReturn(1L);
        when(sessionMapper.update(isNull(), any())).thenReturn(0);
        assertCode(() -> service().switchModel(1L, new SwitchModelRequest("p", "m")),
                ErrorCode.SESSION_SWITCH_FORBIDDEN);

        when(sessionMapper.selectById(2L)).thenReturn(session(2L, "IDLE"), session(2L, "LOCKED"));
        assertCode(() -> service().switchModel(2L, new SwitchModelRequest("p", "m")),
                ErrorCode.SESSION_LOCKED);
    }
}
