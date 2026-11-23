package com.jobpilot.modules.job.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * JD 解析 Prompt（§6.4）：外置模板 + 显式字段清单；JD 解析不属于场景枚举，走全局默认模型。
 */
@Component
public class JobPositionPromptFactory {

    /** 解析 Prompt 版本，随模板语义变化递增。 */
    public static final String VERSION = "jd-parse-v1";

    private final String systemTemplate;
    private final String userTemplate;
    private final String ocrSystemTemplate;
    private final String ocrUserTemplate;

    public JobPositionPromptFactory() {
        this.systemTemplate = read("prompts/job-position-parse-system.st");
        this.userTemplate = read("prompts/job-position-parse-user.st");
        this.ocrSystemTemplate = read("prompts/job-position-ocr-system.st");
        this.ocrUserTemplate = read("prompts/job-position-ocr-user.st");
    }

    public String system() {
        return systemTemplate;
    }

    public String user(String jdText) {
        return userTemplate.replace("{{jdText}}", jdText);
    }

    /** 截图 OCR（FR2.8）：纯文本输出，不做结构化。 */
    public String ocrSystem() {
        return ocrSystemTemplate;
    }

    public String ocrUser() {
        return ocrUserTemplate;
    }

    private static String read(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("加载 Prompt 模板失败: " + path, ex);
        }
    }
}
