package com.jobpilot.modules.resume.prompt;

import com.jobpilot.modules.prompt.service.PromptRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 简历对比总结 Prompt（F7）：差异事实由本地计算后注入，模型只负责点评；
 * 模板来自运行时注册表。
 */
@Component
public class ResumeComparisonPromptFactory {

    private static final String KEY = "resume-comparison";

    private final PromptRegistry registry;

    /** 测试 / CLI：不查 DB，只读内置模板。 */
    public ResumeComparisonPromptFactory() {
        this(PromptRegistry.builtinOnly());
    }

    @Autowired
    public ResumeComparisonPromptFactory(PromptRegistry registry) {
        this.registry = registry;
    }

    public String system() {
        return registry.template(KEY).system();
    }

    public String user(String base, String target, String diff) {
        return registry.template(KEY).user()
                .replace("{{base}}", base)
                .replace("{{target}}", target)
                .replace("{{diff}}", diff);
    }

    /** 本次调用实际使用的提示词版本。 */
    public String version() {
        return registry.version(KEY);
    }
}
