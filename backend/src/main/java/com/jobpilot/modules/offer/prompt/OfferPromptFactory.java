package com.jobpilot.modules.offer.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Offer 提取与分析 Prompt（FR6.1 / FR6.4）：模板外置，联网开关决定来源约束。 */
@Component
public class OfferPromptFactory {

    private final String extractSystem;
    private final String extractUser;
    private final String analysisSystem;
    private final String analysisUser;

    public OfferPromptFactory() {
        this.extractSystem = read("prompts/offer-extract-system.st");
        this.extractUser = read("prompts/offer-extract-user.st");
        this.analysisSystem = read("prompts/offer-analysis-system.st");
        this.analysisUser = read("prompts/offer-analysis-user.st");
    }

    public String extractSystem() {
        return extractSystem;
    }

    public String extractUser(String text) {
        return extractUser.replace("{{text}}", text == null ? "" : text);
    }

    public String analysisSystem(boolean webSearch) {
        return analysisSystem.replace("{{searchRule}}", webSearch
                ? "已开启联网：可结合公开信息补充判断，并在 sources 中列出依据来源（不得编造链接）。"
                : "未开启联网：sources 必须返回空数组，结论只能基于用户提供的资料。");
    }

    public String analysisUser(String materials) {
        return analysisUser.replace("{{materials}}", materials == null ? "" : materials);
    }

    private static String read(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("加载 Prompt 模板失败: " + path, ex);
        }
    }
}
