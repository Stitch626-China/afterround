package com.jobpilot.modules.insights.controller;

import com.jobpilot.common.api.PageResult;
import com.jobpilot.common.api.Result;
import com.jobpilot.modules.insights.dto.InsightDtos.InsightEventItem;
import com.jobpilot.modules.insights.dto.InsightDtos.InsightsOverview;
import com.jobpilot.modules.insights.dto.InsightDtos.InsightsSummaryRequest;
import com.jobpilot.modules.insights.dto.InsightDtos.InsightsSummaryResponse;
import com.jobpilot.modules.insights.service.InsightsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 跨场洞察 API（contracts/openapi.yaml 1.19.0：概览 + 面试明细 + AI 总结 3 个接口）。 */
@RestController
@RequestMapping("/api/insights")
public class InsightsController {

    private final InsightsService insightsService;

    public InsightsController(InsightsService insightsService) {
        this.insightsService = insightsService;
    }

    @GetMapping
    public Result<InsightsOverview> get(
            @RequestParam(defaultValue = "90") int days,
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) Long jobPositionId) {
        return Result.ok(insightsService.overview(days, companyId, jobPositionId));
    }

    @GetMapping("/interview-events")
    public Result<PageResult<InsightEventItem>> events(
            @RequestParam(defaultValue = "90") int days,
            @RequestParam(required = false) Long companyId,
            @RequestParam(required = false) Long jobPositionId,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String roundType,
            @RequestParam(required = false) String topic,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {
        return Result.ok(insightsService.events(days, companyId, jobPositionId, result, roundType,
                topic, page, size));
    }

    @PostMapping("/summary")
    public Result<InsightsSummaryResponse> summary(
            @RequestBody(required = false) InsightsSummaryRequest request) {
        return Result.ok(insightsService.summarize(request));
    }
}
