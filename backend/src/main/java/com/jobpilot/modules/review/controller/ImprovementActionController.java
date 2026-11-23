package com.jobpilot.modules.review.controller;

import com.jobpilot.common.api.PageResult;
import com.jobpilot.common.api.Result;
import com.jobpilot.modules.review.dto.CreateImprovementActionRequest;
import com.jobpilot.modules.review.dto.ImprovementActionResponse;
import com.jobpilot.modules.review.dto.UpdateImprovementActionRequest;
import com.jobpilot.modules.review.service.ImprovementActionService;
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

/** 改进动作 API（contracts/openapi.yaml：F5 闭环 4 个接口）。 */
@RestController
@RequestMapping("/api/improvement-actions")
public class ImprovementActionController {

    private final ImprovementActionService actionService;

    public ImprovementActionController(ImprovementActionService actionService) {
        this.actionService = actionService;
    }

    @GetMapping
    public Result<PageResult<ImprovementActionResponse>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long sourceReviewId) {
        return Result.ok(actionService.page(page, size, status, sourceReviewId));
    }

    @PostMapping
    public Result<ImprovementActionResponse> create(
            @Valid @RequestBody CreateImprovementActionRequest request) {
        return Result.ok(actionService.create(request));
    }

    @PatchMapping("/{actionId}")
    public Result<ImprovementActionResponse> update(@PathVariable Long actionId,
                                                    @RequestBody UpdateImprovementActionRequest request) {
        return Result.ok(actionService.update(actionId, request));
    }

    @DeleteMapping("/{actionId}")
    public Result<Void> delete(@PathVariable Long actionId) {
        actionService.delete(actionId);
        return Result.ok();
    }
}
