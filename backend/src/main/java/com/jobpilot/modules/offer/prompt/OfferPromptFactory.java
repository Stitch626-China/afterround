package com.jobpilot.modules.offer.prompt;

import com.jobpilot.modules.prompt.service.PromptRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Offer 提取与分析 Prompt（FR6.1 / FR6.4）：联网开关决定来源约束；
 * 模板来自运行时注册表，发布后下次调用即生效。
 */
@Component
public class OfferPromptFactory {

    private static final String EXTRACT_KEY = "offer-extract";
    private static final String ANALYSIS_KEY = "offer-analysis";

    private final PromptRegistry registry;

    /** 测试 / CLI：不查 DB，只读内置模板。 */
    public OfferPromptFactory() {
        this(PromptRegistry.builtinOnly());
    }

    @Autowired
    public OfferPromptFactory(PromptRegistry registry) {
        this.registry = registry;
    }

    public String extractSystem() {
        return registry.template(EXTRACT_KEY).system();
    }

    public String extractUser(String text) {
        return registry.template(EXTRACT_KEY).user().replace("{{text}}", text == null ? "" : text);
    }

    public String analysisSystem(boolean webSearch) {
        return registry.template(ANALYSIS_KEY).system().replace("{{searchRule}}", webSearch
                ? "已开启联网：可结合公开信息补充判断，并在 sources 中列出依据来源（不得编造链接）。"
                : "未开启联网：sources 必须返回空数组，结论只能基于用户提供的资料。");
    }

    public String analysisUser(String materials) {
        return registry.template(ANALYSIS_KEY).user().replace("{{materials}}", materials == null ? "" : materials);
    }

    /** 本次 Offer 提取实际使用的提示词版本。 */
    public String extractVersion() {
        return registry.version(EXTRACT_KEY);
    }

    /** 本次 Offer 分析实际使用的提示词版本。 */
    public String analysisVersion() {
        return registry.version(ANALYSIS_KEY);
    }
}
