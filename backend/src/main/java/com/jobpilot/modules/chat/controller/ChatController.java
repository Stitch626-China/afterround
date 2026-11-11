package com.jobpilot.modules.chat.controller;

import java.util.List;

import com.jobpilot.common.api.PageResult;
import com.jobpilot.common.api.Result;
import com.jobpilot.modules.chat.dto.ChatMessageResponse;
import com.jobpilot.modules.chat.dto.ChatSessionResponse;
import com.jobpilot.modules.chat.dto.ChatStreamEvent;
import com.jobpilot.modules.chat.dto.CreateChatSessionRequest;
import com.jobpilot.modules.chat.dto.SendMessageRequest;
import com.jobpilot.modules.chat.dto.SwitchModelRequest;
import com.jobpilot.modules.chat.service.ChatMessageService;
import com.jobpilot.modules.chat.service.ChatSessionService;
import jakarta.validation.Valid;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 对话会话 API（contracts/openapi.yaml：对话会话 7 个接口）。
 */
@RestController
@RequestMapping("/api/chat/sessions")
public class ChatController {

    private final ChatSessionService sessionService;
    private final ChatMessageService messageService;

    public ChatController(ChatSessionService sessionService, ChatMessageService messageService) {
        this.sessionService = sessionService;
        this.messageService = messageService;
    }

    @GetMapping
    public Result<PageResult<ChatSessionResponse>> list(@RequestParam(required = false) Integer page,
                                                        @RequestParam(required = false) Integer size) {
        return Result.ok(sessionService.page(page, size));
    }

    @PostMapping
    public Result<ChatSessionResponse> create(@Valid @RequestBody CreateChatSessionRequest request) {
        return Result.ok(sessionService.create(request));
    }

    @GetMapping("/{sessionId}")
    public Result<ChatSessionResponse> get(@PathVariable Long sessionId) {
        return Result.ok(sessionService.get(sessionId));
    }

    @DeleteMapping("/{sessionId}")
    public Result<Void> delete(@PathVariable Long sessionId) {
        sessionService.delete(sessionId);
        return Result.ok();
    }

    @PatchMapping("/{sessionId}/model")
    public Result<ChatSessionResponse> switchModel(@PathVariable Long sessionId,
                                                   @Valid @RequestBody SwitchModelRequest request) {
        return Result.ok(sessionService.switchModel(sessionId, request));
    }

    @GetMapping("/{sessionId}/messages")
    public Result<List<ChatMessageResponse>> messages(@PathVariable Long sessionId) {
        return Result.ok(messageService.list(sessionId));
    }

    @PostMapping("/{sessionId}/messages")
    public Flux<ServerSentEvent<ChatStreamEvent>> send(@PathVariable Long sessionId,
                                                       @Valid @RequestBody SendMessageRequest request) {
        return messageService.send(sessionId, request)
                .map(event -> ServerSentEvent.<ChatStreamEvent>builder(event).build());
    }
}
