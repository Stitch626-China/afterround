package com.jobpilot.modules.resume.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 简历对比总结 Prompt（F7）：差异事实由本地计算后注入，模型只负责点评。
 */
@Component
public class ResumeComparisonPromptFactory {

    private final String systemTemplate;
    private final String userTemplate;

    public ResumeComparisonPromptFactory() {
        this.systemTemplate = read("prompts/resume-comparison-system.st");
        this.userTemplate = read("prompts/resume-comparison-user.st");
    }

    public String system() {
        return systemTemplate;
    }

    public String user(String base, String target, String diff) {
        return userTemplate.replace("{{base}}", base)
                .replace("{{target}}", target)
                .replace("{{diff}}", diff);
    }

    private static String read(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("加载 Prompt 模板失败: " + path, ex);
        }
    }
}
