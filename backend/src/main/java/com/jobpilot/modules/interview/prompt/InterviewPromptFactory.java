package com.jobpilot.modules.interview.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import com.jobpilot.modules.interview.service.InterviewDirectionService.Direction;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 模拟面试 Prompt（§6.6）：人格来自 SKILL.md，分类配额来自 skill.meta.yml，
 * 模板外置便于调优而不改代码。
 */
@Component
public class InterviewPromptFactory {

    private final String questionSystem;
    private final String questionUser;
    private final String followUpSystem;
    private final String followUpUser;
    private final String evaluateSystem;
    private final String evaluateUser;

    public InterviewPromptFactory() {
        this.questionSystem = read("prompts/interview-question-system.st");
        this.questionUser = read("prompts/interview-question-user.st");
        this.followUpSystem = read("prompts/interview-followup-system.st");
        this.followUpUser = read("prompts/interview-followup-user.st");
        this.evaluateSystem = read("prompts/interview-evaluate-system.st");
        this.evaluateUser = read("prompts/interview-evaluate-user.st");
    }

    public String questionSystem(Direction direction, int questionCount, int followUpCount, boolean hasResume) {
        return questionSystem
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
        return questionUser
                .replace("{{resumeSection}}", resumeSection)
                .replace("{{jdSection}}", jdSection)
                .replace("{{historyTopics}}", topics);
    }

    public String followUpSystem(Direction direction, int followUpCount) {
        return followUpSystem
                .replace("{{persona}}", direction.persona())
                .replace("{{followUpCount}}", String.valueOf(followUpCount));
    }

    public String followUpUser(String directionLabel, String mainContent, String answer) {
        return followUpUser
                .replace("{{directionLabel}}", directionLabel == null ? "" : directionLabel)
                .replace("{{mainContent}}", mainContent == null ? "" : mainContent)
                .replace("{{answer}}", answer == null ? "" : answer);
    }

    public String evaluateSystem(Direction direction) {
        return evaluateSystem
                .replace("{{persona}}", direction.persona())
                .replace("{{categories}}", categories(direction));
    }

    public String evaluateUser(String directionLabel, String transcript) {
        return evaluateUser
                .replace("{{directionLabel}}", directionLabel == null ? "" : directionLabel)
                .replace("{{transcript}}", transcript == null ? "" : transcript);
    }

    private static String categories(Direction direction) {
        return direction.categories().stream()
                .map(category -> category.key() + " " + category.label() + " " + category.weight() + "%")
                .collect(Collectors.joining("；"));
    }

    private static String read(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("加载 Prompt 模板失败: " + path, ex);
        }
    }
}
