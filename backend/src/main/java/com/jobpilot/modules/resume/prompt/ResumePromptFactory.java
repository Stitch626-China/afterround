package com.jobpilot.modules.resume.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 简历分析 Prompt（§6.4）：system / user 模板外置在 resources/prompts 下，调整无需改代码。
 */
@Component
public class ResumePromptFactory {

    private final String systemTemplate;
    private final String userTemplate;

    public ResumePromptFactory() {
        this.systemTemplate = read("prompts/resume-analysis-system.st");
        this.userTemplate = read("prompts/resume-analysis-user.st");
    }

    public String system() {
        return systemTemplate;
    }

    public String user(String resumeText) {
        return userTemplate.replace("{{resumeText}}", resumeText);
    }

    private static String read(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("加载 Prompt 模板失败: " + path, ex);
        }
    }
}
