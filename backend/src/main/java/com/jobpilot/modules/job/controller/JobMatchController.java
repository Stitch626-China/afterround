package com.jobpilot.modules.job.controller;

import com.jobpilot.common.api.PageResult;
import com.jobpilot.common.api.Result;
import com.jobpilot.modules.job.dto.CreateJobMatchRequest;
import com.jobpilot.modules.job.dto.JobMatchResponse;
import com.jobpilot.modules.job.service.JobMatchService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 简历-JD 匹配 API（contracts/openapi.yaml：匹配 3 个接口）。 */
@RestController
@RequestMapping("/api/job-matches")
public class JobMatchController {

    private final JobMatchService matchService;

    public JobMatchController(JobMatchService matchService) {
        this.matchService = matchService;
    }

    @GetMapping
    public Result<PageResult<JobMatchResponse>> list(@RequestParam(required = false) Integer page,
                                                     @RequestParam(required = false) Integer size,
                                                     @RequestParam(required = false) Long resumeId,
                                                     @RequestParam(required = false) Long jobPositionId) {
        return Result.ok(matchService.page(page, size, resumeId, jobPositionId));
    }

    @PostMapping
    public Result<JobMatchResponse> create(@Valid @RequestBody CreateJobMatchRequest request) {
        return Result.ok(matchService.create(request));
    }

    @GetMapping("/{matchId}")
    public Result<JobMatchResponse> detail(@PathVariable Long matchId) {
        return Result.ok(matchService.status(matchId));
    }
}
