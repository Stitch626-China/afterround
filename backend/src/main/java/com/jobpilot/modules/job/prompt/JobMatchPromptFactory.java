package com.jobpilot.modules.job.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 匹配评分 Prompt（§6.4）：权重 50/30/20 在 system 模板显式声明，调权重不改代码。
 */
@Component
public class JobMatchPromptFactory {

    private final String systemTemplate;
    private final String userTemplate;

    public JobMatchPromptFactory() {
        this.systemTemplate = read("prompts/job-match-system.st");
        this.userTemplate = read("prompts/job-match-user.st");
    }

    public String system() {
        return systemTemplate;
    }

    public String user(String resumeText, String jdText) {
        return userTemplate.replace("{{resumeText}}", resumeText).replace("{{jdText}}", jdText);
    }

    private static String read(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("加载 Prompt 模板失败: " + path, ex);
        }
    }
}
