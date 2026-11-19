package com.jobpilot.modules.interview.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 提交单题作答（契约 AnswerInterviewQuestionRequest）。 */
public record AnswerInterviewQuestionRequest(
        @NotBlank @Size(max = 5000) String answer) {
}
