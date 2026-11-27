package com.jobpilot.modules.prompt.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.jobpilot.modules.prompt.catalog.PromptOutputMode;
import com.jobpilot.modules.prompt.catalog.PromptSpec;
import org.springframework.stereotype.Component;

/**
 * 提示词静态校验：占位符完整 + JSON 字段名仍在。
 *
 * <p>这是发布前的护栏，不是 JSON Schema 校验器；字段类型/结构仍由真实调用与评测兜底。
 */
@Component
public class PromptValidator {

    private static final int MAX_LENGTH = 20_000;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z0-9_]+)}}");

    public ValidationResult validate(PromptSpec spec, String system, String user) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String systemText = system == null ? "" : system;
        String userText = user == null ? "" : user;

        if (systemText.isBlank()) {
            errors.add("system 模板不能为空");
        }
        if (userText.isBlank()) {
            errors.add("user 模板不能为空");
        }
        if (systemText.length() > MAX_LENGTH || userText.length() > MAX_LENGTH) {
            errors.add("system/user 单段不能超过 " + MAX_LENGTH + " 字符");
        }

        String combined = systemText + "\n" + userText;
        Set<String> present = placeholders(combined);
        for (String required : spec.placeholders()) {
            if (!present.contains(required)) {
                errors.add("缺少占位符 {{" + required + "}}");
            }
        }
        for (String found : present) {
            if (!spec.placeholders().contains(found)) {
                warnings.add("未知占位符 {{" + found + "}}（不会被替换）");
            }
        }

        if (spec.outputMode() == PromptOutputMode.JSON) {
            for (String field : spec.requiredFields()) {
                if (!containsField(systemText, field)) {
                    errors.add("system 模板缺少必需字段 " + field);
                }
            }
        }
        return new ValidationResult(errors.isEmpty(), List.copyOf(errors), List.copyOf(warnings));
    }

    private static Set<String> placeholders(String text) {
        Set<String> result = new java.util.LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return result;
    }

    private static boolean containsField(String system, String field) {
        Pattern pattern = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(field) + "(?![A-Za-z0-9_])");
        return pattern.matcher(system).find();
    }

    /** 校验结果；errors 非空时不能发布，warnings 只提示。 */
    public record ValidationResult(boolean valid, List<String> errors, List<String> warnings) {
    }
}
