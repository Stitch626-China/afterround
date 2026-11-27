package com.jobpilot.modules.resume.prompt;

import com.jobpilot.modules.prompt.service.PromptRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 简历分析 Prompt（§6.4）：模板来自运行时注册表，DB 生效版本优先、classpath 兜底；
 * 发布后下次调用即生效，无需重启。
 */
@Component
public class ResumePromptFactory {

    /** 内置版本号，仅用于文档与评测展示；运行时以 {@link #version()} 为准。 */
    public static final String VERSION = "resume-analysis-v3";
    private static final String KEY = "resume-analysis";

    private final PromptRegistry registry;

    /** 测试 / CLI：不查 DB，只读内置模板。 */
    public ResumePromptFactory() {
        this(PromptRegistry.builtinOnly());
    }

    @Autowired
    public ResumePromptFactory(PromptRegistry registry) {
        this.registry = registry;
    }

    public String system() {
        return registry.template(KEY).system();
    }

    /** 岗位库在招方向图谱（FR1.7 轨二）；为空时明确告知模型按简历判断，不得编造岗位。 */
    public String user(String resumeText, String marketDirections) {
        String market = marketDirections == null || marketDirections.isBlank()
                ? "（岗位库暂无可统计的在招岗位，按简历本身判断）"
                : marketDirections;
        return registry.template(KEY).user()
                .replace("{{marketDirections}}", market)
                .replace("{{resumeText}}", resumeText);
    }

    /** 本次调用实际使用的提示词版本：内置 vN 或 DB 覆盖版本。 */
    public String version() {
        return registry.version(KEY);
    }
}
