package com.jobpilot.modules.job.prompt;

import com.jobpilot.modules.prompt.service.PromptRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * JD 解析 Prompt（§6.4）：外置模板 + 显式字段清单；JD 解析不属于场景枚举，走全局默认模型。
 * 模板来自运行时注册表，发布后下次调用即生效。
 */
@Component
public class JobPositionPromptFactory {

    /** 内置解析版本，仅用于文档与评测展示；运行时以 {@link #parseVersion()} 为准。 */
    public static final String VERSION = "jd-parse-v1";
    private static final String PARSE_KEY = "job-position-parse";
    private static final String OCR_KEY = "job-position-ocr";

    private final PromptRegistry registry;

    /** 测试 / CLI：不查 DB，只读内置模板。 */
    public JobPositionPromptFactory() {
        this(PromptRegistry.builtinOnly());
    }

    @Autowired
    public JobPositionPromptFactory(PromptRegistry registry) {
        this.registry = registry;
    }

    public String system() {
        return registry.template(PARSE_KEY).system();
    }

    public String user(String jdText) {
        return registry.template(PARSE_KEY).user().replace("{{jdText}}", jdText);
    }

    /** 截图 OCR（FR2.8）：纯文本输出，不做结构化。 */
    public String ocrSystem() {
        return registry.template(OCR_KEY).system();
    }

    public String ocrUser() {
        return registry.template(OCR_KEY).user();
    }

    /** 本次 JD 解析实际使用的提示词版本。 */
    public String parseVersion() {
        return registry.version(PARSE_KEY);
    }

    /** 本次截图 OCR 实际使用的提示词版本。 */
    public String ocrVersion() {
        return registry.version(OCR_KEY);
    }
}
