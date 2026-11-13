package com.jobpilot.modules.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.jobpilot.LambdaCacheSupport;
import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.common.llm.ChatMessage;
import com.jobpilot.common.llm.LlmClient;
import com.jobpilot.infrastructure.llm.LlmCallLogger;
import com.jobpilot.infrastructure.llm.ProviderClientRegistry;
import com.jobpilot.modules.chat.dto.ChatStreamEvent;
import com.jobpilot.modules.chat.dto.SendMessageRequest;
import com.jobpilot.modules.chat.entity.ChatSession;
import com.jobpilot.modules.chat.mapper.ChatMessageMapper;
import com.jobpilot.modules.chat.mapper.ChatSessionMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/** 流式对话守卫：取消/异常/上游建连失败都必须释放 PROCESSING，冲突时返回明确错误码。 */
class ChatMessageServiceTest {

    @BeforeAll
    static void initLambdaCache() {
        LambdaCacheSupport.init(ChatSession.class);
    }

    private static ChatSession session(String status) {
        ChatSession session = new ChatSession();
        session.setId(1L);
        session.setStatus(status);
        session.setCurrentProviderKey("fake");
        session.setCurrentModel("m");
        return session;
    }

    private static ChatMessageService service(ChatSessionMapper sessionMapper, ChatMessageMapper messageMapper,
                                              ChatSessionService sessionService, ProviderClientRegistry registry,
                                              LlmCallLogger callLogger) {
        return new ChatMessageService(sessionMapper, messageMapper, sessionService, registry, callLogger, 50);
    }

    @Test
    void cancelReleasesProcessingStatus() {
        ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        ChatSessionService sessionService = mock(ChatSessionService.class);
        ProviderClientRegistry registry = mock(ProviderClientRegistry.class);
        LlmCallLogger callLogger = mock(LlmCallLogger.class);
        LlmClient client = mock(LlmClient.class);

        when(sessionService.require(1L)).thenReturn(session("IDLE"));
        when(sessionMapper.update(isNull(), any())).thenReturn(1);
        when(messageMapper.selectList(any())).thenReturn(List.of());
        when(registry.get("fake")).thenReturn(client);
        when(client.stream(anyString(), any())).thenReturn(Flux.never());

        Disposable subscription = service(sessionMapper, messageMapper, sessionService, registry, callLogger)
                .send(1L, new SendMessageRequest("hi")).subscribe();
        subscription.dispose();

        // claim 一次 + doFinally 释放一次
        verify(sessionMapper, atLeast(2)).update(isNull(), any());
    }

    @Test
    void streamErrorEmitsErrorEventAndReleases() {
        ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        ChatSessionService sessionService = mock(ChatSessionService.class);
        ProviderClientRegistry registry = mock(ProviderClientRegistry.class);
        LlmCallLogger callLogger = mock(LlmCallLogger.class);
        LlmClient client = mock(LlmClient.class);

        when(sessionService.require(1L)).thenReturn(session("IDLE"));
        when(sessionMapper.update(isNull(), any())).thenReturn(1);
        when(messageMapper.selectList(any())).thenReturn(List.of());
        when(registry.get("fake")).thenReturn(client);
        when(client.stream(anyString(), any())).thenReturn(Flux.error(new IllegalStateException("boom")));

        List<ChatStreamEvent> events = service(sessionMapper, messageMapper, sessionService, registry, callLogger)
                .send(1L, new SendMessageRequest("hi")).collectList().block();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo("error");
        // 失败不落 ASSISTANT 消息，但仍要释放 PROCESSING
        verify(messageMapper, times(1)).insert(any(com.jobpilot.modules.chat.entity.ChatMessage.class));
        verify(sessionMapper, atLeast(2)).update(isNull(), any());
        verify(callLogger).log(any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq(false), any());
    }

    @Test
    void clientCreationFailureReleasesAndRethrows() {
        ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        ChatSessionService sessionService = mock(ChatSessionService.class);
        ProviderClientRegistry registry = mock(ProviderClientRegistry.class);
        LlmCallLogger callLogger = mock(LlmCallLogger.class);

        when(sessionService.require(1L)).thenReturn(session("IDLE"));
        when(sessionMapper.update(isNull(), any())).thenReturn(1);
        when(registry.get("fake")).thenThrow(new IllegalStateException("no client"));

        assertThatThrownBy(() -> service(sessionMapper, messageMapper, sessionService, registry, callLogger)
                .send(1L, new SendMessageRequest("hi")))
                .isInstanceOf(IllegalStateException.class);
        verify(sessionMapper, times(2)).update(isNull(), any());
    }

    @Test
    void sendRejectsLockedSession() {
        ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        ChatSessionService sessionService = mock(ChatSessionService.class);
        ProviderClientRegistry registry = mock(ProviderClientRegistry.class);
        LlmCallLogger callLogger = mock(LlmCallLogger.class);

        when(sessionService.require(1L)).thenReturn(session("IDLE"), session("LOCKED"));
        when(sessionMapper.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> service(sessionMapper, messageMapper, sessionService, registry, callLogger)
                .send(1L, new SendMessageRequest("hi")))
                .isInstanceOfSatisfying(BizException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.SESSION_LOCKED));
        verify(messageMapper, never()).insert(any(com.jobpilot.modules.chat.entity.ChatMessage.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void historyDropsLeadingAssistantHalfRound() {
        ChatSessionMapper sessionMapper = mock(ChatSessionMapper.class);
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        ChatSessionService sessionService = mock(ChatSessionService.class);
        ProviderClientRegistry registry = mock(ProviderClientRegistry.class);
        LlmCallLogger callLogger = mock(LlmCallLogger.class);
        LlmClient client = mock(LlmClient.class);

        com.jobpilot.modules.chat.entity.ChatMessage orphan =
                new com.jobpilot.modules.chat.entity.ChatMessage();
        orphan.setRole("ASSISTANT");
        orphan.setContent("旧回答");
        com.jobpilot.modules.chat.entity.ChatMessage user = new com.jobpilot.modules.chat.entity.ChatMessage();
        user.setRole("USER");
        user.setContent("新问题");
        when(sessionService.require(1L)).thenReturn(session("IDLE"));
        when(sessionMapper.update(isNull(), any())).thenReturn(1);
        when(messageMapper.selectList(any())).thenReturn(List.of(orphan, user));
        when(registry.get("fake")).thenReturn(client);
        when(client.stream(anyString(), any())).thenReturn(Flux.empty());

        service(sessionMapper, messageMapper, sessionService, registry, callLogger)
                .send(1L, new SendMessageRequest("hi")).collectList().block();

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(client).stream(anyString(), captor.capture());
        assertThat(captor.getValue()).extracting(ChatMessage::role).doesNotContain("ASSISTANT");
    }
}
