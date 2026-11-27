package com.jobpilot.modules.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.modules.prompt.catalog.PromptCatalog;
import com.jobpilot.modules.prompt.catalog.PromptSpec;
import com.jobpilot.modules.prompt.service.PromptValidator;
import com.jobpilot.modules.prompt.service.PromptValidator.ValidationResult;
import org.junit.jupiter.api.Test;

class PromptValidatorTest {

    private final PromptCatalog catalog = new PromptCatalog();
    private final PromptValidator validator = new PromptValidator();

    @Test
    void missingPlaceholderIsInvalid() {
        PromptSpec spec = catalog.get("resume-analysis");
        ValidationResult result = validator.validate(spec,
                "technicalDepth projectQuality engineeringAbility expressionStructure jobMatchPotential "
                        + "basicInfo strengths weaknesses suggestions careerDirections",
                "简历：{{resumeText}}");
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("marketDirections"));
    }

    @Test
    void missingRequiredFieldBlocksPublish() {
        PromptSpec spec = catalog.get("resume-analysis");
        ValidationResult result = validator.validate(spec, "只输出 JSON",
                "{{resumeText}} {{marketDirections}}");
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("technicalDepth"));
    }

    @Test
    void textPromptSkipsFieldCheck() {
        PromptSpec spec = catalog.get("insights-summary");
        ValidationResult result = validator.validate(spec, "用中文总结", "{{days}} 天：{{data}}");
        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void unknownPlaceholderIsWarningOnly() {
        PromptSpec spec = catalog.get("job-position-parse");
        ValidationResult result = validator.validate(spec,
                "title companyName city salaryMin salaryMax experience education mustHaveSkills "
                        + "niceToHaveSkills responsibility",
                "{{jdText}} {{unknown}}");
        assertThat(result.valid()).isTrue();
        assertThat(result.warnings()).anyMatch(warning -> warning.contains("unknown"));
    }

    @Test
    void emptyTemplateIsInvalid() {
        ValidationResult result = validator.validate(catalog.get("job-position-ocr"), " ", "");
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).hasSize(2);
    }
}
