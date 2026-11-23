package com.jobpilot.modules.review.controller;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.api.PageResult;
import com.jobpilot.common.api.Result;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.modules.review.dto.ReviewAnalysisUpdateRequest;
import com.jobpilot.modules.review.dto.ReviewSessionDetailResponse;
import com.jobpilot.modules.review.dto.ReviewSessionListItemResponse;
import com.jobpilot.modules.review.dto.ReviewSessionStatusResponse;
import com.jobpilot.modules.review.dto.UpdateReviewSessionRequest;
import com.jobpilot.modules.review.service.ReviewSessionService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 真实面试复盘 API（contracts/openapi.yaml：F4 8 个接口）。 */
@RestController
@RequestMapping("/api/review-sessions")
public class ReviewSessionController {

    private final ReviewSessionService reviewService;

    public ReviewSessionController(ReviewSessionService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping
    public Result<PageResult<ReviewSessionListItemResponse>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long applicationId) {
        return Result.ok(reviewService.page(page, size, status, applicationId));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<ReviewSessionListItemResponse> create(
            @RequestPart(value = "audio", required = false) MultipartFile audio,
            @RequestParam(value = "transcript", required = false) String transcript,
            @RequestParam(value = "notes", required = false) String notes,
            @RequestParam(value = "applicationId", required = false) Long applicationId,
            @RequestParam(value = "jobPositionId", required = false) Long jobPositionId,
            @RequestParam(value = "roundNo", required = false) Integer roundNo,
            @RequestParam(value = "roundType", required = false) String roundType,
            @RequestParam(value = "interviewerRole", required = false) String interviewerRole,
            @RequestParam(value = "format", required = false) String format,
            @RequestParam(value = "interviewTime", required = false) String interviewTime,
            @RequestParam(value = "idempotencyKey", required = false) String idempotencyKey) {
        return Result.ok(reviewService.create(audio, transcript, notes, applicationId, jobPositionId, roundNo,
                roundType, interviewerRole, format, time(interviewTime), idempotencyKey));
    }

    @GetMapping("/{reviewSessionId}")
    public Result<ReviewSessionDetailResponse> detail(@PathVariable Long reviewSessionId) {
        return Result.ok(reviewService.detail(reviewSessionId));
    }

    @PatchMapping("/{reviewSessionId}")
    public Result<ReviewSessionDetailResponse> update(@PathVariable Long reviewSessionId,
                                                      @RequestBody UpdateReviewSessionRequest request) {
        return Result.ok(reviewService.update(reviewSessionId, request));
    }

    @DeleteMapping("/{reviewSessionId}")
    public Result<Void> delete(@PathVariable Long reviewSessionId) {
        reviewService.delete(reviewSessionId);
        return Result.ok();
    }

    @GetMapping("/{reviewSessionId}/status")
    public Result<ReviewSessionStatusResponse> status(@PathVariable Long reviewSessionId) {
        return Result.ok(reviewService.status(reviewSessionId));
    }

    @PostMapping("/{reviewSessionId}/analyze")
    public Result<ReviewSessionListItemResponse> analyze(@PathVariable Long reviewSessionId) {
        return Result.ok(reviewService.analyze(reviewSessionId));
    }

    @PutMapping("/{reviewSessionId}/analysis")
    public Result<ReviewSessionDetailResponse> updateAnalysis(
            @PathVariable Long reviewSessionId,
            @Valid @RequestBody ReviewAnalysisUpdateRequest request) {
        return Result.ok(reviewService.updateAnalysis(reviewSessionId, request));
    }

    private static OffsetDateTime time(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "面试时间格式非法，需为 ISO-8601");
        }
    }
}
