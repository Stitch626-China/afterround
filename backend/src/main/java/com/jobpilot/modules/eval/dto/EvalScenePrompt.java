package com.jobpilot.modules.eval.dto;

/** 启动时的提示词快照（对外只暴露 key / 版本 / 哈希）。 */
public record EvalScenePrompt(String scene, String promptKey, String promptVersion, String promptHash) {
}
