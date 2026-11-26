package com.jobpilot.modules.llm.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.modules.llm.dto.LlmUsageResponse;
import com.jobpilot.modules.llm.mapper.LlmCallLogMapper;
import org.springframework.stereotype.Service;

/**
 * token 成本统计（NFR6）：只读聚合 llm_call_logs，按场景 / 模型 / 按天汇总；
 * 无调用时比例为 null，不输出无意义的 0%。
 */
@Service
public class LlmUsageService {

    private final LlmCallLogMapper logMapper;

    public LlmUsageService(LlmCallLogMapper logMapper) {
        this.logMapper = logMapper;
    }

    public LlmUsageResponse usage(Integer days) {
        int window = days == null ? 30 : days;
        if (window < 7 || window > 365) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "days 需在 7~365 之间");
        }
        OffsetDateTime from = OffsetDateTime.now().minusDays(window);
        Map<String, Object> summary = logMapper.usageSummary(from);
        int calls = count(summary, "calls");
        int successCalls = count(summary, "success_calls");
        return new LlmUsageResponse(window, OffsetDateTime.now(),
                new LlmUsageResponse.Summary(calls, successCalls, rate(successCalls, calls),
                        number(summary, "prompt_tokens"), number(summary, "completion_tokens"),
                        number(summary, "total_tokens"), number(summary, "avg_duration_ms")),
                logMapper.usageByScene(from).stream().map(row -> {
                    int sceneCalls = count(row, "calls");
                    int sceneSuccess = count(row, "success_calls");
                    String scene = text(row, "scene");
                    return new LlmUsageResponse.Scene(scene, label(scene), sceneCalls, sceneSuccess,
                            rate(sceneSuccess, sceneCalls), number(row, "total_tokens"),
                            number(row, "avg_duration_ms"));
                }).toList(),
                logMapper.usageByModel(from).stream()
                        .map(row -> new LlmUsageResponse.Model(text(row, "provider_key"), text(row, "model"),
                                count(row, "calls"), number(row, "total_tokens")))
                        .toList(),
                logMapper.usageDaily(from).stream()
                        .map(row -> new LlmUsageResponse.Day(text(row, "day"), count(row, "calls"),
                                number(row, "total_tokens")))
                        .toList());
    }

    /** 场景枚举 + 自由场景名（JD 解析 / 截图 / 洞察总结等）统一中文展示。 */
    private static String label(String scene) {
        if (scene == null) {
            return "未知场景";
        }
        return switch (scene) {
            case "CHAT" -> "模型对话";
            case "RESUME_ANALYSIS", "RESUME_ANALYZE" -> "简历分析";
            case "RESUME_COMPARISON" -> "简历对比";
            case "JOB_MATCH" -> "岗位匹配";
            case "JD_PARSE" -> "JD 解析";
            case "JD_OCR" -> "JD 截图识别";
            case "INTERVIEW_QUESTION" -> "模拟面试出题";
            case "INTERVIEW_EVALUATE" -> "模拟面试评估";
            case "OFFER_EXTRACT" -> "Offer 信息提取";
            case "OFFER_ANALYZE" -> "Offer 分析";
            case "REVIEW_ANALYZE" -> "复盘分析";
            case "INSIGHTS_SUMMARY" -> "洞察 AI 总结";
            case "CONNECTIVITY_TEST" -> "连通测试";
            default -> scene;
        };
    }

    private static Double rate(int success, int calls) {
        return calls == 0 ? null : Math.round((double) success / calls * 10_000) / 10_000.0;
    }

    private static int count(Map<String, Object> row, String key) {
        return (int) number(row, key);
    }

    private static long number(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
