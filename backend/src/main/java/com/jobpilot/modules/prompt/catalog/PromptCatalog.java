package com.jobpilot.modules.prompt.catalog;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.modules.llm.dto.LlmScene;
import org.springframework.stereotype.Component;

/** 12 组提示词的唯一元数据目录；新增调用方时必须同步 affectedFunctions / evalScene。 */
@Component
public class PromptCatalog {

    private static final List<PromptSpec> SPECS = List.of(
            new PromptSpec("resume-analysis", "简历分析", "档案", "评估简历并生成五维报告与岗位方向",
                    "prompts/resume-analysis-system.st", "prompts/resume-analysis-user.st",
                    List.of("resumeText", "marketDirections"),
                    List.of("technicalDepth", "projectQuality", "engineeringAbility", "expressionStructure",
                            "jobMatchPotential", "basicInfo", "strengths", "weaknesses", "suggestions",
                            "careerDirections"),
                    PromptOutputMode.JSON, PromptModelScope.SCENE, LlmScene.RESUME_ANALYZE, "resume-analysis",
                    List.of("简历分析报告", "简历对比的可比性判定"), "resume-analysis-v3"),

            new PromptSpec("resume-comparison", "简历对比", "档案", "对两份简历的差异做 LLM 总结点评",
                    "prompts/resume-comparison-system.st", "prompts/resume-comparison-user.st",
                    List.of("base", "target", "diff"),
                    List.of("summary"),
                    PromptOutputMode.JSON, PromptModelScope.GLOBAL, null, null,
                    List.of("简历对比总结"), "resume-comparison-v1"),

            new PromptSpec("job-position-parse", "JD 解析", "岗位", "把 JD 原文解析为结构化岗位字段",
                    "prompts/job-position-parse-system.st", "prompts/job-position-parse-user.st",
                    List.of("jdText"),
                    List.of("title", "companyName", "city", "salaryMin", "salaryMax", "experience", "education",
                            "mustHaveSkills", "niceToHaveSkills", "responsibility"),
                    PromptOutputMode.JSON, PromptModelScope.GLOBAL, null, "jd-parse",
                    List.of("JD 导入解析", "岗位详情"), "jd-parse-v1"),

            new PromptSpec("job-position-ocr", "JD 截图 OCR", "岗位", "识别 JD 截图中的纯文本，交给解析链路",
                    "prompts/job-position-ocr-system.st", "prompts/job-position-ocr-user.st",
                    List.of(),
                    List.of(),
                    PromptOutputMode.TEXT, PromptModelScope.GLOBAL, null, null,
                    List.of("JD 截图导入"), "job-position-ocr-v1"),

            new PromptSpec("job-match", "岗位匹配", "岗位", "按技能/经验/加分项三项评估简历与 JD 匹配度",
                    "prompts/job-match-system.st", "prompts/job-match-user.st",
                    List.of("resumeText", "jdText"),
                    List.of("skillMatch", "experienceMatch", "bonusCoverage", "gaps", "preparationAdvice"),
                    PromptOutputMode.JSON, PromptModelScope.SCENE, LlmScene.JOB_MATCH, null,
                    List.of("岗位匹配评分"), "job-match-v1"),

            new PromptSpec("review-analyze", "复盘分析", "复盘", "从面试转写/笔记还原问答、评分、标签与行动",
                    "prompts/review-analyze-system.st", "prompts/review-analyze-user.st",
                    List.of("companyName", "jobTitle", "roundType", "interviewerRole", "companyIntro", "jdText",
                            "resumeText", "text"),
                    List.of("questions", "topics", "lossTags", "summary", "actions"),
                    PromptOutputMode.JSON, PromptModelScope.SCENE, LlmScene.REVIEW_ANALYZE, "review-analyze",
                    List.of("面试复盘分析"), "review-analyze-v2"),

            new PromptSpec("insights-summary", "洞察总结", "复盘", "把结构化统计压成一段可读的求职洞察总结",
                    "prompts/insights-summary-system.st", "prompts/insights-summary-user.st",
                    List.of("days", "data"),
                    List.of(),
                    PromptOutputMode.TEXT, PromptModelScope.SCENE, LlmScene.REVIEW_ANALYZE, null,
                    List.of("求职洞察 AI 总结"), "insights-summary-v1"),

            new PromptSpec("interview-question", "模拟面试出题", "面试", "按方向 Skill 配额生成主问题",
                    "prompts/interview-question-system.st", "prompts/interview-question-user.st",
                    List.of("persona", "questionCount", "followUpCount", "sourceRule", "categories", "resumeSection",
                            "jdSection", "historyTopics"),
                    List.of("questions"),
                    PromptOutputMode.JSON, PromptModelScope.SCENE, LlmScene.INTERVIEW_QUESTION, null,
                    List.of("模拟面试出题"), "interview-question-v1"),

            new PromptSpec("interview-followup", "模拟面试追问", "面试", "基于主问题与回答生成追问",
                    "prompts/interview-followup-system.st", "prompts/interview-followup-user.st",
                    List.of("persona", "followUpCount", "directionLabel", "mainContent", "answer"),
                    List.of("questions"),
                    PromptOutputMode.JSON, PromptModelScope.SESSION, null, null,
                    List.of("模拟面试追问"), "interview-followup-v1"),

            new PromptSpec("interview-evaluate", "模拟面试评估", "面试", "统一评估整场模拟面试并给出分类得分",
                    "prompts/interview-evaluate-system.st", "prompts/interview-evaluate-user.st",
                    List.of("persona", "categories", "directionLabel", "transcript"),
                    List.of("questions", "categories", "strengths", "improvements", "summary"),
                    PromptOutputMode.JSON, PromptModelScope.SCENE, LlmScene.INTERVIEW_EVALUATE, null,
                    List.of("模拟面试评估"), "interview-evaluate-v1"),

            new PromptSpec("offer-extract", "Offer 提取", "Offer", "从 Offer 文本或图片中提取固定字段",
                    "prompts/offer-extract-system.st", "prompts/offer-extract-user.st",
                    List.of("text"),
                    List.of("companyName", "jobPositionTitle", "monthlySalary", "baseSalary", "performanceRatio",
                            "socialInsuranceBase", "socialInsuranceRatio", "housingFundBase", "housingFundRatio",
                            "twoDayWeekend", "workHours", "benefits", "allowances", "annualBonus", "quarterlyBonus",
                            "remark"),
                    PromptOutputMode.JSON, PromptModelScope.GLOBAL, null, null,
                    List.of("Offer 信息提取"), "offer-extract-v1"),

            new PromptSpec("offer-analysis", "Offer 分析", "Offer", "分析 Offer 前景、风险与倾向",
                    "prompts/offer-analysis-system.st", "prompts/offer-analysis-user.st",
                    List.of("searchRule", "materials"),
                    List.of("tendency", "reasons", "prospect", "risks", "sources"),
                    PromptOutputMode.JSON, PromptModelScope.SCENE, LlmScene.OFFER_ANALYZE, null,
                    List.of("Offer 前景与倾向分析"), "offer-analysis-v1"));

    private final Map<String, PromptSpec> byKey = SPECS.stream()
            .collect(Collectors.toUnmodifiableMap(PromptSpec::key, Function.identity()));

    /** 全部提示词，顺序稳定，供工作台列表使用。 */
    public List<PromptSpec> all() {
        return SPECS;
    }

    /** 按 key 取元数据；未知 key 视为资源不存在。 */
    public PromptSpec get(String key) {
        PromptSpec spec = byKey.get(key);
        if (spec == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "提示词不存在: " + key);
        }
        return spec;
    }

    public boolean contains(String key) {
        return byKey.containsKey(key);
    }
}
