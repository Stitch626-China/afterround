package com.jobpilot.modules.job.controller;

import com.jobpilot.common.api.PageResult;
import com.jobpilot.common.api.Result;
import com.jobpilot.modules.job.dto.BatchDeleteJobPositionsRequest;
import com.jobpilot.modules.job.dto.JobPositionDetailResponse;
import com.jobpilot.modules.job.dto.JobPositionListItemResponse;
import com.jobpilot.modules.job.dto.UpdateJobPositionRequest;
import com.jobpilot.modules.job.service.JobPositionService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 岗位库 API（contracts/openapi.yaml：岗位库 6 个接口）。 */
@RestController
@RequestMapping("/api/job-positions")
public class JobPositionController {

    private final JobPositionService jobService;

    public JobPositionController(JobPositionService jobService) {
        this.jobService = jobService;
    }

    @GetMapping
    public Result<PageResult<JobPositionListItemResponse>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String keyword) {
        return Result.ok(jobService.page(page, size, status, city, keyword));
    }

    @PostMapping
    public Result<JobPositionListItemResponse> importJd(
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "text", required = false) String text,
            @RequestPart(value = "images", required = false) List<MultipartFile> images) {
        return Result.ok(jobService.importJd(file, text, images));
    }

    @PostMapping("/batch-delete")
    public Result<Void> batchDelete(@Valid @RequestBody BatchDeleteJobPositionsRequest request) {
        jobService.batchDelete(request);
        return Result.ok();
    }

    @GetMapping("/{jobPositionId}")
    public Result<JobPositionDetailResponse> detail(@PathVariable Long jobPositionId) {
        return Result.ok(jobService.detail(jobPositionId));
    }

    @PutMapping("/{jobPositionId}")
    public Result<JobPositionListItemResponse> update(@PathVariable Long jobPositionId,
                                                      @Valid @RequestBody UpdateJobPositionRequest request) {
        return Result.ok(jobService.update(jobPositionId, request));
    }

    @DeleteMapping("/{jobPositionId}")
    public Result<Void> delete(@PathVariable Long jobPositionId) {
        jobService.delete(jobPositionId);
        return Result.ok();
    }

    @PostMapping("/{jobPositionId}/parse")
    public Result<JobPositionListItemResponse> retryParse(@PathVariable Long jobPositionId) {
        return Result.ok(jobService.retryParse(jobPositionId));
    }
}
