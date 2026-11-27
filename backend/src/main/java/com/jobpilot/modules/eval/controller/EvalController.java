package com.jobpilot.modules.eval.controller;

import java.util.List;

import com.jobpilot.common.api.PageResult;
import com.jobpilot.common.api.Result;
import com.jobpilot.modules.eval.dto.CreateEvalRunRequest;
import com.jobpilot.modules.eval.dto.EvalRunDetail;
import com.jobpilot.modules.eval.dto.EvalRunSummary;
import com.jobpilot.modules.eval.dto.EvalSceneSummary;
import com.jobpilot.modules.eval.service.EvalRunService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 评测运行 API（contracts/openapi.yaml 1.23.0，5 个端点）。 */
@RestController
@RequestMapping("/api/llm/eval")
public class EvalController {

    private final EvalRunService service;

    public EvalController(EvalRunService service) {
        this.service = service;
    }

    @GetMapping("/scenes")
    public Result<List<EvalSceneSummary>> scenes() {
        return Result.ok(service.scenes());
    }

    @PostMapping("/runs")
    public Result<EvalRunDetail> create(@Valid @RequestBody CreateEvalRunRequest request) {
        return Result.ok(service.create(request));
    }

    @GetMapping("/runs")
    public Result<PageResult<EvalRunSummary>> list(@RequestParam(defaultValue = "1") Integer page,
                                                   @RequestParam(defaultValue = "20") Integer size) {
        return Result.ok(service.list(page, size));
    }

    @GetMapping("/runs/{runId}")
    public Result<EvalRunDetail> detail(@PathVariable Long runId) {
        return Result.ok(service.detail(runId));
    }

    @PostMapping("/runs/{runId}/cancel")
    public Result<EvalRunDetail> cancel(@PathVariable Long runId) {
        return Result.ok(service.cancel(runId));
    }
}
