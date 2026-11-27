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

    /** 简历分析 Prompt 版本，随模板语义变化递增；F7 对比据此判定结果可比性。 */
    public static final String VERSION = "resume-analysis-v3";

    private final String systemTemplate;
    private final String userTemplate;

    public ResumePromptFactory() {
        this.systemTemplate = read("prompts/resume-analysis-system.st");
        this.userTemplate = read("prompts/resume-analysis-user.st");
    }

    public String system() {
        return systemTemplate;
    }

    /** 岗位库在招方向图谱（FR1.7 轨二）；为空时明确告知模型按简历判断，不得编造岗位。 */
    public String user(String resumeText, String marketDirections) {
        String market = marketDirections == null || marketDirections.isBlank()
                ? "（岗位库暂无可统计的在招岗位，按简历本身判断）"
                : marketDirections;
        return userTemplate
                .replace("{{marketDirections}}", market)
                .replace("{{resumeText}}", resumeText);
    }

    private static String read(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("加载 Prompt 模板失败: " + path, ex);
        }
    }
}
