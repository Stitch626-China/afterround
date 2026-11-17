package com.jobpilot.modules.llm.controller;

import java.util.List;

import com.jobpilot.common.api.Result;
import com.jobpilot.modules.llm.dto.BindSceneModelRequest;
import com.jobpilot.modules.llm.dto.LlmScene;
import com.jobpilot.modules.llm.dto.SceneBindingResponse;
import com.jobpilot.modules.llm.service.LlmSceneService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 场景级模型绑定 API（contracts/openapi.yaml：模型配置 - 场景绑定）。 */
@RestController
@RequestMapping("/api/llm/scenes")
public class LlmSceneController {

    private final LlmSceneService sceneService;

    public LlmSceneController(LlmSceneService sceneService) {
        this.sceneService = sceneService;
    }

    @GetMapping
    public Result<List<SceneBindingResponse>> list() {
        return Result.ok(sceneService.list());
    }

    @PutMapping("/{scene}")
    public Result<SceneBindingResponse> bind(@PathVariable LlmScene scene,
                                             @Valid @RequestBody BindSceneModelRequest request) {
        return Result.ok(sceneService.bind(scene, request));
    }

    @DeleteMapping("/{scene}")
    public Result<SceneBindingResponse> unbind(@PathVariable LlmScene scene) {
        return Result.ok(sceneService.unbind(scene));
    }
}
