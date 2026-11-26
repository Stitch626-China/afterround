package com.jobpilot.modules.insights.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 洞察 AI 总结 Prompt（FR5.8）：外置模板，只注入结构化统计，不注入原始转写。
 * 总结失败由服务层回退本地规则总结，不阻塞页面。
 */
@Component
public class InsightsPromptFactory {

    /** Prompt 版本，随模板语义变化递增。 */
    public static final String VERSION = "insights-summary-v1";

    private final String systemTemplate;
    private final String userTemplate;

    public InsightsPromptFactory() {
        this.systemTemplate = read("prompts/insights-summary-system.st");
        this.userTemplate = read("prompts/insights-summary-user.st");
    }

    public String system() {
        return systemTemplate;
    }

    public String user(int days, String data) {
        return userTemplate
                .replace("{{days}}", String.valueOf(days))
                .replace("{{data}}", data);
    }

    private static String read(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("加载 Prompt 模板失败: " + path, ex);
        }
    }
}
