package com.jobpilot.infrastructure.llm;

import com.jobpilot.common.web.TraceIdFilter;
import com.jobpilot.modules.llm.entity.LlmCallLog;
import com.jobpilot.modules.llm.mapper.LlmCallLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * LLM 调用埋点（NFR2 / NFR6）：每次调用落库，失败不阻断主流程。
 */
@Component
public class LlmCallLogger {

    private static final Logger log = LoggerFactory.getLogger(LlmCallLogger.class);

    private final LlmCallLogMapper mapper;

    public LlmCallLogger(LlmCallLogMapper mapper) {
        this.mapper = mapper;
    }

    /** 记录一次调用；token 缺失时允许传 null。 */
    public void log(String scene, String providerKey, String model,
                    Integer promptTokens, Integer completionTokens, Integer totalTokens,
                    long durationMs, boolean success, String error) {
        try {
            LlmCallLog entry = new LlmCallLog();
            entry.setUserId(1L);
            entry.setTraceId(MDC.get(TraceIdFilter.MDC_KEY));
            entry.setScene(scene);
            entry.setProviderKey(providerKey);
            entry.setModel(model);
            entry.setPromptTokens(promptTokens);
            entry.setCompletionTokens(completionTokens);
            entry.setTotalTokens(totalTokens);
            entry.setDurationMs(durationMs);
            entry.setSuccess(success);
            entry.setError(error == null ? null : error.substring(0, Math.min(error.length(), 500)));
            mapper.insert(entry);
        } catch (Exception ex) {
            log.warn("写入 llm_call_logs 失败 scene={} provider={} model={}", scene, providerKey, model, ex);
        }
    }
}
