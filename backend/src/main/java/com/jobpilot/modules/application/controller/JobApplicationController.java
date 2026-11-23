package com.jobpilot.modules.application.controller;

import com.jobpilot.common.api.PageResult;
import com.jobpilot.common.api.Result;
import com.jobpilot.modules.application.dto.CreateJobApplicationRequest;
import com.jobpilot.modules.application.dto.JobApplicationListItemResponse;
import com.jobpilot.modules.application.dto.JobApplicationTimelineResponse;
import com.jobpilot.modules.application.dto.UpdateJobApplicationRequest;
import com.jobpilot.modules.application.service.JobApplicationService;
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

/** 投递档案 API（contracts/openapi.yaml：F8 5 个接口）。 */
@RestController
@RequestMapping("/api/job-applications")
public class JobApplicationController {

    private final JobApplicationService applicationService;

    public JobApplicationController(JobApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @GetMapping
    public Result<PageResult<JobApplicationListItemResponse>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long jobPositionId,
            @RequestParam(required = false) Long resumeId,
            @RequestParam(required = false) String keyword) {
        return Result.ok(applicationService.page(page, size, status, jobPositionId, resumeId, keyword));
    }

    @PostMapping
    public Result<JobApplicationListItemResponse> create(
            @Valid @RequestBody CreateJobApplicationRequest request) {
        return Result.ok(applicationService.create(request));
    }

    @PatchMapping("/{applicationId}")
    public Result<JobApplicationListItemResponse> update(
            @PathVariable Long applicationId,
            @RequestBody UpdateJobApplicationRequest request) {
        return Result.ok(applicationService.update(applicationId, request));
    }

    @DeleteMapping("/{applicationId}")
    public Result<Void> delete(@PathVariable Long applicationId) {
        applicationService.delete(applicationId);
        return Result.ok();
    }

    @GetMapping("/{applicationId}/timeline")
    public Result<JobApplicationTimelineResponse> timeline(@PathVariable Long applicationId) {
        return Result.ok(applicationService.timeline(applicationId));
    }
}
