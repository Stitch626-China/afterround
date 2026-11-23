package com.jobpilot.modules.review.controller;

import com.jobpilot.common.api.PageResult;
import com.jobpilot.common.api.Result;
import com.jobpilot.modules.review.dto.CreateInterviewEventRequest;
import com.jobpilot.modules.review.dto.InterviewEventListItemResponse;
import com.jobpilot.modules.review.dto.UpdateInterviewEventRequest;
import com.jobpilot.modules.review.service.InterviewEventService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 真实面试轮次 API（contracts/openapi.yaml：F4/F5 4 个接口）。 */
@RestController
@RequestMapping("/api/interview-events")
public class InterviewEventController {

    private final InterviewEventService eventService;

    public InterviewEventController(InterviewEventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping
    public Result<PageResult<InterviewEventListItemResponse>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Long applicationId,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String roundType) {
        return Result.ok(eventService.page(page, size, applicationId, result, roundType));
    }

    @PostMapping
    public Result<InterviewEventListItemResponse> create(
            @Valid @RequestBody CreateInterviewEventRequest request) {
        return Result.ok(eventService.create(request));
    }

    @PatchMapping("/{eventId}")
    public Result<InterviewEventListItemResponse> update(
            @PathVariable Long eventId,
            @RequestBody UpdateInterviewEventRequest request) {
        return Result.ok(eventService.update(eventId, request));
    }

    @DeleteMapping("/{eventId}")
    public Result<Void> delete(@PathVariable Long eventId) {
        eventService.delete(eventId);
        return Result.ok();
    }
}
