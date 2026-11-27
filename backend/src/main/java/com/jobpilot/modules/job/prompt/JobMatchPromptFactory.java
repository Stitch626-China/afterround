package com.jobpilot.modules.job.prompt;

import com.jobpilot.modules.prompt.service.PromptRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 匹配评分 Prompt（§6.4）：权重 50/30/20 在 system 模板显式声明，调权重不改代码；
 * 模板来自运行时注册表。
 */
@Component
public class JobMatchPromptFactory {

    private static final String KEY = "job-match";

    private final PromptRegistry registry;

    /** 测试 / CLI：不查 DB，只读内置模板。 */
    public JobMatchPromptFactory() {
        this(PromptRegistry.builtinOnly());
    }

    @Autowired
    public JobMatchPromptFactory(PromptRegistry registry) {
        this.registry = registry;
    }

    public String system() {
        return registry.template(KEY).system();
    }

    public String user(String resumeText, String jdText) {
        return registry.template(KEY).user()
                .replace("{{resumeText}}", resumeText)
                .replace("{{jdText}}", jdText);
    }

    /** 本次调用实际使用的提示词版本。 */
    public String version() {
        return registry.version(KEY);
    }
}
