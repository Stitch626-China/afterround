package com.jobpilot.modules.job.dto;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 更新岗位（契约 UpdateJobPositionRequest）：全部字段可选，仅传入的字段生效。
 *
 * <p>companyId 用 {@link JsonNode} 区分「未传」与「显式 null」：未传不修改，
 * 显式 null 表示解除公司关联（契约约定）。
 */
public record UpdateJobPositionRequest(
        @Size(max = 128) String title,
        JsonNode companyId,
        @Size(max = 64) String city,
        @Min(0) @Max(1000000) Integer salaryMin,
        @Min(0) @Max(1000000) Integer salaryMax,
        @Size(max = 64) String experience,
        @Size(max = 64) String education,
        List<@Size(max = 64) String> mustHaveSkills,
        List<@Size(max = 64) String> niceToHaveSkills,
        @Size(max = 5000) String responsibility,
        @Pattern(regexp = "SAVED|APPLIED|INTERVIEWING|OFFERED|REJECTED", message = "status 取值非法") String status,
        @Size(max = 200) String remark) {
}
