package com.jobpilot.modules.company.dto;

import jakarta.validation.constraints.NotNull;

/** 手动合并公司（契约 MergeCompaniesRequest）：源公司并入目标公司后删除。 */
public record MergeCompaniesRequest(@NotNull Long sourceCompanyId, @NotNull Long targetCompanyId) {
}
