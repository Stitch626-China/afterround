package com.jobpilot.modules.resume.controller;

import com.jobpilot.common.api.Result;
import com.jobpilot.modules.resume.dto.CreateResumeComparisonRequest;
import com.jobpilot.modules.resume.dto.ResumeComparisonResponse;
import com.jobpilot.modules.resume.service.ResumeComparisonService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 简历版本对比 API（contracts/openapi.yaml：简历对比 1 个接口）。 */
@RestController
@RequestMapping("/api/resume-comparisons")
public class ResumeComparisonController {

    private final ResumeComparisonService comparisonService;

    public ResumeComparisonController(ResumeComparisonService comparisonService) {
        this.comparisonService = comparisonService;
    }

    @PostMapping
    public Result<ResumeComparisonResponse> compare(
            @Valid @RequestBody CreateResumeComparisonRequest request) {
        return Result.ok(comparisonService.compare(request));
    }
}
