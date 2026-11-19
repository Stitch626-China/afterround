package com.jobpilot.modules.interview.controller;

import java.util.List;

import com.jobpilot.common.api.Result;
import com.jobpilot.modules.interview.dto.InterviewDirection;
import com.jobpilot.modules.interview.service.InterviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 面试方向 API（contracts/openapi.yaml：GET /api/interview/directions）。 */
@RestController
@RequestMapping("/api/interview")
public class InterviewController {

    private final InterviewService interviewService;

    public InterviewController(InterviewService interviewService) {
        this.interviewService = interviewService;
    }

    @GetMapping("/directions")
    public Result<List<InterviewDirection>> directions() {
        return Result.ok(interviewService.directions());
    }
}
