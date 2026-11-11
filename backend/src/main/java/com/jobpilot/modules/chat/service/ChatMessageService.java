package com.jobpilot.modules.chat.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.common.llm.ChatMessage;
import com.jobpilot.common.llm.LlmClient;
import com.jobpilot.common.web.TraceIdFilter;
import com.jobpilot.infrastructure.llm.LlmCallLogger;
import com.jobpilot.infrastructure.llm.ProviderClientRegistry;
import com.jobpilot.modules.chat.dto.ChatMessageResponse;
import com.jobpilot.modules.chat.dto.ChatStreamEvent;
import com.jobpilot.modules.chat.dto.SendMessageRequest;
import com.jobpilot.modules.chat.entity.ChatSession;
import com.jobpilot.modules.chat.mapper.ChatMessageMapper;
import com.jobpilot.modules.chat.mapper.ChatSessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 消息与流式对话（FRX.5 / FRX.8 / FRX.9）。
 *
 * <p>并发守卫用「条件更新」原子抢占 PROCESSING，避免两个请求同时通过状态检查；
 * 流结束后落 ASSISTANT 消息并释放为 IDLE，失败则回 IDLE 并推送 error 事件。
 */
@Service
public class ChatMessageService {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageService.class);

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final ChatSessionService sessionService;
    private final ProviderClientRegistry registry;
    private final LlmCallLogger callLogger;
    private final int maxHistory;

    public ChatMessageService(ChatSessionMapper sessionMapper, ChatMessageMapper messageMapper,
                              ChatSessionService sessionService, ProviderClientRegistry registry,
                              LlmCallLogger callLogger,
                              @Value("${app.llm.max-history-messages:50}") int maxHistory) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.sessionService = sessionService;
        this.registry = registry;
        this.callLogger = callLogger;
        this.maxHistory = maxHistory;
    }

    /** 按时间正序返回全部消息。 */
    public List<ChatMessageResponse> list(Long sessionId) {
        sessionService.require(sessionId);
        return messageMapper.selectList(Wrappers.<com.jobpilot.modules.chat.entity.ChatMessage>lambdaQuery()
                        .eq(com.jobpilot.modules.chat.entity.ChatMessage::getSessionId, sessionId)
                        .orderByAsc(com.jobpilot.modules.chat.entity.ChatMessage::getId))
                .stream().map(ChatMessageResponse::from).toList();
    }

    /** 发送消息并返回 SSE 事件流；状态冲突在返回 Flux 前以业务异常抛出（409 JSON）。 */
    public Flux<ChatStreamEvent> send(Long sessionId, SendMessageRequest request) {
        ChatSession session = sessionService.require(sessionId);
        if (!claim(sessionId)) {
            ChatSession latest = sessionService.require(sessionId);
            throw BizException.of("LOCKED".equals(latest.getStatus())
                    ? ErrorCode.SESSION_LOCKED : ErrorCode.SESSION_SWITCH_FORBIDDEN);
        }
        String providerKey = session.getCurrentProviderKey();
        String model = session.getCurrentModel();
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        try {
            save(sessionId, "USER", request.content(), null, null, traceId);
            List<ChatMessage> history = history(sessionId);
            LlmClient client = registry.get(providerKey);
            StringBuilder answer = new StringBuilder();
            AtomicReference<String> actualModel = new AtomicReference<>(model);
            AtomicReference<Integer> totalTokens = new AtomicReference<>();
            long start = System.nanoTime();
            return client.stream(model, history)
                    .<ChatStreamEvent>handle((chunk, sink) -> {
                        if (chunk.model() != null) {
                            actualModel.set(chunk.model());
                        }
                        if (chunk.totalTokens() != null) {
                            totalTokens.set(chunk.totalTokens());
                        }
                        if (chunk.content() != null) {
                            answer.append(chunk.content());
                            sink.next(ChatStreamEvent.delta(chunk.content()));
                        }
                    })
                    .concatWith(Flux.defer(() -> Mono.fromCallable(() -> {
                        com.jobpilot.modules.chat.entity.ChatMessage saved = save(sessionId, "ASSISTANT",
                                answer.toString(), providerKey, actualModel.get(), traceId);
                        release(sessionId);
                        callLogger.log("CHAT", providerKey, actualModel.get(), null, null,
                                totalTokens.get(), elapsed(start), true, null);
                        return ChatStreamEvent.done(saved.getId(), providerKey, actualModel.get(), totalTokens.get());
                    }).subscribeOn(Schedulers.boundedElastic()).flux()))
                    .onErrorResume(ex -> Mono.fromCallable(() -> {
                        release(sessionId);
                        callLogger.log("CHAT", providerKey, model, null, null, null,
                                elapsed(start), false, ex.getMessage());
                        log.warn("会话 {} 流式对话失败: {}", sessionId, ex.getMessage());
                        return ChatStreamEvent.error(ErrorCode.INTERNAL_ERROR.code(),
                                ex.getMessage() == null ? "模型调用失败" : ex.getMessage());
                    }).subscribeOn(Schedulers.boundedElastic()).flux());
        } catch (RuntimeException ex) {
            release(sessionId);
            throw ex;
        }
    }

    /** 原子抢占：仅 IDLE → PROCESSING 成功。 */
    private boolean claim(Long sessionId) {
        return sessionMapper.update(null, Wrappers.<ChatSession>lambdaUpdate()
                .eq(ChatSession::getId, sessionId)
                .eq(ChatSession::getStatus, "IDLE")
                .set(ChatSession::getStatus, "PROCESSING")
                .set(ChatSession::getUpdatedAt, OffsetDateTime.now())) > 0;
    }

    /** 释放回 IDLE（只从 PROCESSING 释放，幂等）。 */
    private void release(Long sessionId) {
        sessionMapper.update(null, Wrappers.<ChatSession>lambdaUpdate()
                .eq(ChatSession::getId, sessionId)
                .eq(ChatSession::getStatus, "PROCESSING")
                .set(ChatSession::getStatus, "IDLE")
                .set(ChatSession::getUpdatedAt, OffsetDateTime.now()));
    }

    private com.jobpilot.modules.chat.entity.ChatMessage save(Long sessionId, String role, String content,
                                                              String providerKey, String model, String traceId) {
        com.jobpilot.modules.chat.entity.ChatMessage entity = new com.jobpilot.modules.chat.entity.ChatMessage();
        entity.setUserId(1L);
        entity.setSessionId(sessionId);
        entity.setRole(role);
        entity.setContent(content);
        entity.setLlmProvider(providerKey);
        entity.setLlmModel(model);
        entity.setTraceId(traceId);
        messageMapper.insert(entity);
        return entity;
    }

    /** 组装上下文：最近 N 条消息（含刚写入的 USER 消息），按时间正序。 */
    private List<ChatMessage> history(Long sessionId) {
        List<com.jobpilot.modules.chat.entity.ChatMessage> recent = messageMapper.selectList(
                Wrappers.<com.jobpilot.modules.chat.entity.ChatMessage>lambdaQuery()
                        .eq(com.jobpilot.modules.chat.entity.ChatMessage::getSessionId, sessionId)
                        .orderByDesc(com.jobpilot.modules.chat.entity.ChatMessage::getId)
                        .last("LIMIT " + maxHistory));
        List<ChatMessage> messages = new ArrayList<>(recent.size());
        Collections.reverse(recent);
        for (com.jobpilot.modules.chat.entity.ChatMessage entity : recent) {
            messages.add("USER".equals(entity.getRole())
                    ? ChatMessage.user(entity.getContent())
                    : ChatMessage.assistant(entity.getContent()));
        }
        return messages;
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
