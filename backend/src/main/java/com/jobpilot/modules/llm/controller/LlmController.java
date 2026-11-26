package com.jobpilot.modules.llm.controller;

import java.util.List;

import com.jobpilot.common.api.Result;
import com.jobpilot.modules.llm.dto.ConnectivityTestResponse;
import com.jobpilot.modules.llm.dto.CreateModelRequest;
import com.jobpilot.modules.llm.dto.CreateProviderRequest;
import com.jobpilot.modules.llm.dto.GlobalSettingResponse;
import com.jobpilot.modules.llm.dto.LlmModelResponse;
import com.jobpilot.modules.llm.dto.LlmProviderResponse;
import com.jobpilot.modules.llm.dto.LlmUsageResponse;
import com.jobpilot.modules.llm.dto.TestProviderRequest;
import com.jobpilot.modules.llm.dto.UpdateGlobalSettingRequest;
import com.jobpilot.modules.llm.dto.UpdateModelRequest;
import com.jobpilot.modules.llm.dto.UpdateProviderRequest;
import com.jobpilot.modules.llm.dto.UpstreamModelListResponse;
import com.jobpilot.modules.llm.service.LlmGlobalSettingService;
import com.jobpilot.modules.llm.service.LlmModelService;
import com.jobpilot.modules.llm.service.LlmProviderService;
import com.jobpilot.modules.llm.service.LlmQueryService;
import com.jobpilot.modules.llm.service.LlmUsageService;
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

/**
 * 模型配置 API（contracts/openapi.yaml 1.20.0：模型配置 12 个接口 + token 成本统计 1 个）。
 */
@RestController
@RequestMapping("/api/llm")
public class LlmController {

    private final LlmProviderService providerService;
    private final LlmModelService modelService;
    private final LlmGlobalSettingService globalService;
    private final LlmQueryService queryService;
    private final LlmUsageService usageService;

    public LlmController(LlmProviderService providerService, LlmModelService modelService,
                         LlmGlobalSettingService globalService, LlmQueryService queryService,
                         LlmUsageService usageService) {
        this.providerService = providerService;
        this.modelService = modelService;
        this.globalService = globalService;
        this.queryService = queryService;
        this.usageService = usageService;
    }

    @GetMapping("/providers")
    public Result<List<LlmProviderResponse>> listProviders() {
        return Result.ok(providerService.list());
    }

    @PostMapping("/providers")
    public Result<LlmProviderResponse> createProvider(@Valid @RequestBody CreateProviderRequest request) {
        return Result.ok(providerService.create(request));
    }

    @GetMapping("/providers/{providerKey}")
    public Result<LlmProviderResponse> getProvider(@PathVariable String providerKey) {
        return Result.ok(providerService.get(providerKey));
    }

    @PutMapping("/providers/{providerKey}")
    public Result<LlmProviderResponse> updateProvider(@PathVariable String providerKey,
                                                      @Valid @RequestBody UpdateProviderRequest request) {
        return Result.ok(providerService.update(providerKey, request));
    }

    @DeleteMapping("/providers/{providerKey}")
    public Result<Void> deleteProvider(@PathVariable String providerKey) {
        providerService.delete(providerKey);
        return Result.ok();
    }

    @PostMapping("/providers/{providerKey}/test")
    public Result<ConnectivityTestResponse> testProvider(@PathVariable String providerKey,
                                                         @RequestBody(required = false) TestProviderRequest request) {
        return Result.ok(queryService.test(providerKey, request));
    }

    @GetMapping("/providers/{providerKey}/models")
    public Result<UpstreamModelListResponse> listModels(@PathVariable String providerKey) {
        return Result.ok(queryService.listUpstreamModels(providerKey));
    }

    @PostMapping("/providers/{providerKey}/models")
    public Result<LlmModelResponse> createModel(@PathVariable String providerKey,
                                                @Valid @RequestBody CreateModelRequest request) {
        return Result.ok(modelService.create(providerKey, request));
    }

    @PutMapping("/providers/{providerKey}/models/{modelName}")
    public Result<LlmModelResponse> updateModel(@PathVariable String providerKey, @PathVariable String modelName,
                                                @Valid @RequestBody UpdateModelRequest request) {
        return Result.ok(modelService.update(providerKey, modelName, request));
    }

    @DeleteMapping("/providers/{providerKey}/models/{modelName}")
    public Result<Void> deleteModel(@PathVariable String providerKey, @PathVariable String modelName) {
        modelService.delete(providerKey, modelName);
        return Result.ok();
    }

    @GetMapping("/global")
    public Result<GlobalSettingResponse> getGlobalSetting() {
        return Result.ok(globalService.get());
    }

    @PutMapping("/global")
    public Result<GlobalSettingResponse> updateGlobalSetting(@Valid @RequestBody UpdateGlobalSettingRequest request) {
        return Result.ok(globalService.update(request));
    }

    @GetMapping("/usage")
    public Result<LlmUsageResponse> usage(@RequestParam(defaultValue = "30") Integer days) {
        return Result.ok(usageService.usage(days));
    }
}
