package com.jobpilot.modules.insights.controller;

import com.jobpilot.common.api.Result;
import com.jobpilot.modules.insights.dto.InsightDtos.InsightsOverview;
import com.jobpilot.modules.insights.service.InsightsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 跨场洞察 API（contracts/openapi.yaml 1.10.0：F5 1 个接口）。 */
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
}
