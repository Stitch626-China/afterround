package com.jobpilot.modules.insights.prompt;

import com.jobpilot.modules.prompt.service.PromptRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 洞察 AI 总结 Prompt（FR5.8）：只注入结构化统计，不注入原始转写；
 * 模板来自运行时注册表。总结失败由服务层回退本地规则总结，不阻塞页面。
 */
@Component
public class InsightsPromptFactory {

    /** 内置版本号，仅用于文档与评测展示；运行时以 {@link #version()} 为准。 */
    public static final String VERSION = "insights-summary-v1";
    private static final String KEY = "insights-summary";

    private final PromptRegistry registry;

    /** 测试 / CLI：不查 DB，只读内置模板。 */
    public InsightsPromptFactory() {
        this(PromptRegistry.builtinOnly());
    }

    @Autowired
    public InsightsPromptFactory(PromptRegistry registry) {
        this.registry = registry;
    }

    public String system() {
        return registry.template(KEY).system();
    }

    public String user(int days, String data) {
        return registry.template(KEY).user()
                .replace("{{days}}", String.valueOf(days))
                .replace("{{data}}", data);
    }

    /** 本次总结实际使用的提示词版本。 */
    public String version() {
        return registry.version(KEY);
    }
}
