package com.jobpilot.modules.interview.controller;

import java.time.OffsetDateTime;

import com.jobpilot.common.api.PageResult;
import com.jobpilot.common.api.Result;
import com.jobpilot.modules.chat.dto.SwitchModelRequest;
import com.jobpilot.modules.interview.dto.AnswerInterviewQuestionRequest;
import com.jobpilot.modules.interview.dto.CreateInterviewSessionRequest;
import com.jobpilot.modules.interview.dto.InterviewEvaluation;
import com.jobpilot.modules.interview.dto.InterviewQuestion;
import com.jobpilot.modules.interview.dto.InterviewSession;
import com.jobpilot.modules.interview.dto.InterviewSessionDetail;
import com.jobpilot.modules.interview.service.InterviewService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 模拟面试 API（contracts/openapi.yaml：F3 共 10 个会话端点）。 */
@RestController
@RequestMapping("/api/interview-sessions")
public class InterviewSessionController {

    private final InterviewService interviewService;

    public InterviewSessionController(InterviewService interviewService) {
        this.interviewService = interviewService;
    }

    @GetMapping
    public Result<PageResult<InterviewSession>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            OffsetDateTime createdFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            OffsetDateTime createdTo) {
        return Result.ok(interviewService.page(page, size, direction, status, createdFrom, createdTo));
    }

    @PostMapping
    public Result<InterviewSession> create(@Valid @RequestBody CreateInterviewSessionRequest request) {
        return Result.ok(interviewService.create(request));
    }

    @GetMapping("/{sessionId}")
    public Result<InterviewSessionDetail> detail(@PathVariable Long sessionId) {
        return Result.ok(interviewService.detail(sessionId));
    }

    @DeleteMapping("/{sessionId}")
    public Result<Void> delete(@PathVariable Long sessionId) {
        interviewService.delete(sessionId);
        return Result.ok();
    }

    @PatchMapping("/{sessionId}/model")
    public Result<InterviewSession> switchModel(@PathVariable Long sessionId,
                                                @Valid @RequestBody SwitchModelRequest request) {
        return Result.ok(interviewService.switchModel(sessionId, request));
    }

    @PostMapping("/{sessionId}/questions/retry")
    public Result<InterviewSession> retryQuestions(@PathVariable Long sessionId) {
        return Result.ok(interviewService.retryQuestions(sessionId));
    }

    @PostMapping("/{sessionId}/questions/{questionIndex}/answer")
    public Result<InterviewQuestion> answer(@PathVariable Long sessionId, @PathVariable Integer questionIndex,
                                            @Valid @RequestBody AnswerInterviewQuestionRequest request) {
        return Result.ok(interviewService.answer(sessionId, questionIndex, request));
    }

    @PostMapping("/{sessionId}/questions/{questionIndex}/skip")
    public Result<InterviewQuestion> skip(@PathVariable Long sessionId, @PathVariable Integer questionIndex) {
        return Result.ok(interviewService.skip(sessionId, questionIndex));
    }

    @PostMapping("/{sessionId}/submit")
    public Result<InterviewSession> submit(@PathVariable Long sessionId) {
        return Result.ok(interviewService.submit(sessionId));
    }

    @GetMapping("/{sessionId}/evaluation")
    public Result<InterviewEvaluation> evaluation(@PathVariable Long sessionId) {
        return Result.ok(interviewService.evaluation(sessionId));
    }
}
