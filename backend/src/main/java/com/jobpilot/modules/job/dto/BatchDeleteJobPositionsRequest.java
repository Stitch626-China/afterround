package com.jobpilot.modules.job.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 批量删除岗位（契约 BatchDeleteJobPositionsRequest）：1~100 个 id。 */
public record BatchDeleteJobPositionsRequest(
        @NotEmpty @Size(max = 100) List<@NotNull Long> ids) {
}
