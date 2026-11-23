package com.jobpilot.infrastructure.asr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * ASR 配置（FR4.1）：独立于 LLM Provider，走标准 OpenAI 兼容 {@code /audio/transcriptions}。
 *
 * <p>未配置时音频复盘入口返回可操作提示，不写入任务，避免产生无法恢复的卡死记录。
 */
@Component
public class AsrProperties {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String language;
    private final int timeoutSeconds;

    public AsrProperties(@Value("${app.asr.base-url:}") String baseUrl,
                         @Value("${app.asr.api-key:}") String apiKey,
                         @Value("${app.asr.model:whisper-1}") String model,
                         @Value("${app.asr.language:zh}") String language,
                         @Value("${app.asr.timeout-seconds:600}") int timeoutSeconds) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.isBlank() ? "whisper-1" : model.trim();
        this.language = language == null ? "" : language.trim();
        this.timeoutSeconds = timeoutSeconds <= 0 ? 600 : timeoutSeconds;
    }

    public boolean configured() {
        return !baseUrl.isBlank() && !apiKey.isBlank();
    }

    public String baseUrl() {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public String apiKey() {
        return apiKey;
    }

    public String model() {
        return model;
    }

    public String language() {
        return language;
    }

    public int timeoutSeconds() {
        return timeoutSeconds;
    }
}
