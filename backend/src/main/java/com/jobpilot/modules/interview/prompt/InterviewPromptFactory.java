package com.jobpilot.modules.interview.prompt;

import java.util.List;
import java.util.stream.Collectors;

import com.jobpilot.modules.interview.service.InterviewDirectionService.Direction;
import com.jobpilot.modules.prompt.service.PromptRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 模拟面试 Prompt（§6.6）：人格来自 SKILL.md，分类配额来自 skill.meta.yml；
 * 模板来自运行时注册表，发布后下次出题 / 追问 / 评估即生效。
 */
@Component
public class InterviewPromptFactory {

    private static final String QUESTION_KEY = "interview-question";
    private static final String FOLLOW_UP_KEY = "interview-followup";
    private static final String EVALUATE_KEY = "interview-evaluate";

    private final PromptRegistry registry;

    /** 测试 / CLI：不查 DB，只读内置模板。 */
    public InterviewPromptFactory() {
        this(PromptRegistry.builtinOnly());
    }

    @Autowired
    public InterviewPromptFactory(PromptRegistry registry) {
        this.registry = registry;
    }

    public String questionSystem(Direction direction, int questionCount, int followUpCount, boolean hasResume) {
        return registry.template(QUESTION_KEY).system()
                .replace("{{persona}}", direction.persona())
                .replace("{{questionCount}}", String.valueOf(questionCount))
                .replace("{{followUpCount}}", String.valueOf(followUpCount))
                .replace("{{sourceRule}}", hasResume
                        ? "题目约 60% 来自简历项目深挖（FR3.2），约 40% 来自方向知识体系。"
                        : "全部题目来自方向知识体系，按分类配额覆盖。")
                .replace("{{categories}}", categories(direction));
    }

    public String questionUser(String resumeText, String jdText, List<String> historyTopics) {
        String resumeSection = resumeText == null || resumeText.isBlank()
                ? "未关联简历：本次只出方向题。"
                : "候选人简历原文：\n" + resumeText;
        String jdSection = jdText == null || jdText.isBlank()
                ? "未关联岗位 JD。"
                : "目标岗位 JD（出题需贴合岗位要求）：\n" + jdText;
        String topics = historyTopics == null || historyTopics.isEmpty()
                ? "无"
                : historyTopics.stream().collect(Collectors.joining("、"));
        return registry.template(QUESTION_KEY).user()
                .replace("{{resumeSection}}", resumeSection)
                .replace("{{jdSection}}", jdSection)
                .replace("{{historyTopics}}", topics);
    }

    public String followUpSystem(Direction direction, int followUpCount) {
        return registry.template(FOLLOW_UP_KEY).system()
                .replace("{{persona}}", direction.persona())
                .replace("{{followUpCount}}", String.valueOf(followUpCount));
    }

    public String followUpUser(String directionLabel, String mainContent, String answer) {
        return registry.template(FOLLOW_UP_KEY).user()
                .replace("{{directionLabel}}", directionLabel == null ? "" : directionLabel)
                .replace("{{mainContent}}", mainContent == null ? "" : mainContent)
                .replace("{{answer}}", answer == null ? "" : answer);
    }

    public String evaluateSystem(Direction direction) {
        return registry.template(EVALUATE_KEY).system()
                .replace("{{persona}}", direction.persona())
                .replace("{{categories}}", categories(direction));
    }

    public String evaluateUser(String directionLabel, String transcript) {
        return registry.template(EVALUATE_KEY).user()
                .replace("{{directionLabel}}", directionLabel == null ? "" : directionLabel)
                .replace("{{transcript}}", transcript == null ? "" : transcript);
    }

    /** 本次出题实际使用的提示词版本。 */
    public String questionVersion() {
        return registry.version(QUESTION_KEY);
    }

    /** 本次追问实际使用的提示词版本。 */
    public String followUpVersion() {
        return registry.version(FOLLOW_UP_KEY);
    }

    /** 本次评估实际使用的提示词版本。 */
    public String evaluateVersion() {
        return registry.version(EVALUATE_KEY);
    }

    private static String categories(Direction direction) {
        return direction.categories().stream()
                .map(category -> category.key() + " " + category.label() + " " + category.weight() + "%")
                .collect(Collectors.joining("；"));
    }
}
