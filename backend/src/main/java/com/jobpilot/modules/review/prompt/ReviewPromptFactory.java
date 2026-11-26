package com.jobpilot.modules.review.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 复盘分析 Prompt（FR4.3）：外置模板 + 显式 JSON 结构与受控词表；
 * 模型优先读取 REVIEW_ANALYZE 场景绑定，未绑定时回落全局默认。
 */
@Component
public class ReviewPromptFactory {

    /** 分析 Prompt 版本，随模板语义变化递增。 */
    public static final String VERSION = "review-analyze-v2";

    private final String systemTemplate;
    private final String userTemplate;

    public ReviewPromptFactory() {
        this.systemTemplate = read("prompts/review-analyze-system.st");
        this.userTemplate = read("prompts/review-analyze-user.st");
    }

    public String system() {
        return systemTemplate;
    }

    public String user(String companyName, String jobTitle, String roundType, String interviewerRole,
                       String companyIntro, String jdText, String resumeText, String text) {
        return userTemplate
                .replace("{{companyName}}", value(companyName))
                .replace("{{jobTitle}}", value(jobTitle))
                .replace("{{roundType}}", value(roundType))
                .replace("{{interviewerRole}}", value(interviewerRole))
                .replace("{{companyIntro}}", value(companyIntro))
                .replace("{{jdText}}", value(jdText))
                .replace("{{resumeText}}", value(resumeText))
                .replace("{{text}}", text);
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "未填写" : value;
    }

    private static String read(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("加载 Prompt 模板失败: " + path, ex);
        }
    }
}
