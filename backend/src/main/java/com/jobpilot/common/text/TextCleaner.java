package com.jobpilot.common.text;

/**
 * 简历文本清洗（FR1.2）：去控制字符 / 图片引用 / 行尾空白，压缩多余空行，保留段落结构。
 */
public final class TextCleaner {

    private TextCleaner() {
    }

    public static String clean(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "")
                .replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", "")
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n");
        return text.strip();
    }
}
