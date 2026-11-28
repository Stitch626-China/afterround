package com.jobpilot.modules.prompt.controller;

import java.util.List;

import com.jobpilot.common.api.PageResult;
import com.jobpilot.common.api.Result;
import com.jobpilot.modules.prompt.dto.PromptDetail;
import com.jobpilot.modules.prompt.dto.PromptSummary;
import com.jobpilot.modules.prompt.dto.PromptVersionDetail;
import com.jobpilot.modules.prompt.dto.PromptVersionSummary;
import com.jobpilot.modules.prompt.dto.PublishPromptRequest;
import com.jobpilot.modules.prompt.dto.SavePromptDraftRequest;
import com.jobpilot.modules.prompt.service.PromptService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 提示词工作台 API（1.22.0 引入，当前契约 1.25.0，共 8 个端点）。 */
@RestController
@RequestMapping("/api/llm/prompts")
public class PromptController {

    private final PromptService service;

    public PromptController(PromptService service) {
        this.service = service;
    }

    @GetMapping
    public Result<List<PromptSummary>> list() {
        return Result.ok(service.list());
    }

    @GetMapping("/{promptKey}")
    public Result<PromptDetail> detail(@PathVariable String promptKey) {
        return Result.ok(service.detail(promptKey));
    }

    @PutMapping("/{promptKey}/draft")
    public Result<PromptDetail> saveDraft(@PathVariable String promptKey,
                                          @Valid @RequestBody SavePromptDraftRequest request) {
        return Result.ok(service.saveDraft(promptKey, request));
    }

    @DeleteMapping("/{promptKey}/draft")
    public Result<Void> discardDraft(@PathVariable String promptKey) {
        service.discardDraft(promptKey);
        return Result.ok();
    }

    @PostMapping("/{promptKey}/publish")
    public Result<PromptDetail> publish(@PathVariable String promptKey,
                                        @RequestBody(required = false) PublishPromptRequest request) {
        return Result.ok(service.publish(promptKey, request));
    }

    @GetMapping("/{promptKey}/versions")
    public Result<PageResult<PromptVersionSummary>> versions(
            @PathVariable String promptKey,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {
        return Result.ok(service.versions(promptKey, page, size));
    }

    @GetMapping("/{promptKey}/versions/{versionNo}")
    public Result<PromptVersionDetail> version(@PathVariable String promptKey,
                                               @PathVariable int versionNo) {
        return Result.ok(service.version(promptKey, versionNo));
    }

    @PostMapping("/{promptKey}/versions/{versionNo}/activate")
    public Result<PromptDetail> activate(@PathVariable String promptKey,
                                         @PathVariable int versionNo) {
        return Result.ok(service.activate(promptKey, versionNo));
    }
}
