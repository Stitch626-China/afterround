package com.jobpilot.modules.company.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCompanyRequest(
        @NotBlank @Size(max = 128) String name,
        @Size(max = 10000) String intro,
        List<@Size(max = 32) String> tags) {
}
