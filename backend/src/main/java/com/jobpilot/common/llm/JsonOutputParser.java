package com.jobpilot.common.llm;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 输出的本地 JSON 修复（§6.5）：去代码围栏、截取首个完整 JSON、去除尾随逗号。
 *
 * <p>只做字符级、无副作用的修复；修复失败由 {@link StructuredOutputInvoker} 带错误反馈重试。
 */
public final class JsonOutputParser {

    private static final Pattern FENCE = Pattern.compile("(?s)```(?:json)?\\s*(.*?)```");
    private static final Pattern TRAILING_COMMA = Pattern.compile(",\\s*([}\\]])");

    private JsonOutputParser() {
    }

    /** 返回尽量合法的 JSON 文本。 */
    public static String repair(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw.replace("\uFEFF", "").trim();
        Matcher fence = FENCE.matcher(text);
        if (fence.find()) {
            text = fence.group(1).trim();
        }
        String json = extractBalanced(text);
        return TRAILING_COMMA.matcher(json).replaceAll("$1").trim();
    }

    /** 从首个 { 或 [ 开始，按括号配对截取完整 JSON；解析字符串内的括号与转义。 */
    static String extractBalanced(String text) {
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{' || c == '[') {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return text;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return text.substring(start);
    }
}
