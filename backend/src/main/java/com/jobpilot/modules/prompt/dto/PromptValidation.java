package com.jobpilot.modules.prompt.dto;

import java.util.List;

/** 提示词静态校验结果；errors 非空时不能发布。 */
public record PromptValidation(boolean valid, List<String> errors, List<String> warnings) {
}
