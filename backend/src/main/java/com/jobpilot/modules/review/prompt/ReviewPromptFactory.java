package com.jobpilot.modules.review.prompt;

import com.jobpilot.modules.prompt.service.PromptRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 复盘分析 Prompt（FR4.3）：外置模板 + 显式 JSON 结构与受控词表；
 * 模板来自运行时注册表，发布后下次分析即生效。
 */
@Component
public class ReviewPromptFactory {

    /** 内置版本号，仅用于文档与评测展示；运行时以 {@link #version()} 为准。 */
    public static final String VERSION = "review-analyze-v2";
    private static final String KEY = "review-analyze";

    private final PromptRegistry registry;

    /** 测试 / CLI：不查 DB，只读内置模板。 */
    public ReviewPromptFactory() {
        this(PromptRegistry.builtinOnly());
    }

    @Autowired
    public ReviewPromptFactory(PromptRegistry registry) {
        this.registry = registry;
    }

    public String system() {
        return registry.template(KEY).system();
    }

    public String user(String companyName, String jobTitle, String roundType, String interviewerRole,
                       String companyIntro, String jdText, String resumeText, String text) {
        return registry.template(KEY).user()
                .replace("{{companyName}}", value(companyName))
                .replace("{{jobTitle}}", value(jobTitle))
                .replace("{{roundType}}", value(roundType))
                .replace("{{interviewerRole}}", value(interviewerRole))
                .replace("{{companyIntro}}", value(companyIntro))
                .replace("{{jdText}}", value(jdText))
                .replace("{{resumeText}}", value(resumeText))
                .replace("{{text}}", text);
    }

    /** 本次分析实际使用的提示词版本。 */
    public String version() {
        return registry.version(KEY);
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "未填写" : value;
    }
}
