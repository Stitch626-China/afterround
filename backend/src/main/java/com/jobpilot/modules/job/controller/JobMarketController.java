package com.jobpilot.modules.job.controller;

import com.jobpilot.common.api.Result;
import com.jobpilot.modules.job.dto.JobMarketInsightsResponse;
import com.jobpilot.modules.job.service.JobMarketService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 行情洞察 API（contracts/openapi.yaml：行情 1 个接口）。 */
@RestController
@RequestMapping("/api/job-market")
public class JobMarketController {

    private final JobMarketService marketService;

    public JobMarketController(JobMarketService marketService) {
        this.marketService = marketService;
    }

    @GetMapping("/insights")
    public Result<JobMarketInsightsResponse> insights() {
        return Result.ok(marketService.insights());
    }
}
