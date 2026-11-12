package com.jobpilot.modules.resume.controller;

import com.jobpilot.common.api.PageResult;
import com.jobpilot.common.api.Result;
import com.jobpilot.modules.resume.dto.ResumeAnalysisStatusResponse;
import com.jobpilot.modules.resume.dto.ResumeDetailResponse;
import com.jobpilot.modules.resume.dto.ResumeListItemResponse;
import com.jobpilot.modules.resume.dto.ResumeUploadResponse;
import com.jobpilot.modules.resume.service.ResumeAnalysisService;
import com.jobpilot.modules.resume.service.ResumeService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 简历中心 API（contracts/openapi.yaml：简历 6 个接口）。
 */
@RestController
@RequestMapping("/api/resumes")
public class ResumeController {

    private final ResumeService resumeService;
    private final ResumeAnalysisService analysisService;

    public ResumeController(ResumeService resumeService, ResumeAnalysisService analysisService) {
        this.resumeService = resumeService;
        this.analysisService = analysisService;
    }

    @GetMapping
    public Result<PageResult<ResumeListItemResponse>> list(@RequestParam(required = false) Integer page,
                                                           @RequestParam(required = false) Integer size) {
        return Result.ok(resumeService.page(page, size));
    }

    @PostMapping
    public Result<ResumeUploadResponse> upload(
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "text", required = false) String text) {
        return Result.ok(resumeService.upload(file, text));
    }

    @GetMapping("/{resumeId}")
    public Result<ResumeDetailResponse> detail(@PathVariable Long resumeId) {
        return Result.ok(resumeService.detail(resumeId));
    }

    @DeleteMapping("/{resumeId}")
    public Result<Void> delete(@PathVariable Long resumeId) {
        resumeService.delete(resumeId);
        return Result.ok();
    }

    @PostMapping("/{resumeId}/analyze")
    public Result<ResumeAnalysisStatusResponse> analyze(@PathVariable Long resumeId) {
        return Result.ok(analysisService.trigger(resumeId));
    }

    @GetMapping("/{resumeId}/analysis")
    public Result<ResumeAnalysisStatusResponse> analysis(@PathVariable Long resumeId) {
        return Result.ok(analysisService.status(resumeId));
    }
}
