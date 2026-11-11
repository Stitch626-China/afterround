package com.jobpilot.modules.llm.dto;

import java.util.List;

/**
 * 上游模型列表；source = UPSTREAM 表示实时拉取成功，LOCAL_FALLBACK 表示回退库内登记。
 */
public record UpstreamModelListResponse(String source, List<String> models) {

    public static final String UPSTREAM = "UPSTREAM";
    public static final String LOCAL_FALLBACK = "LOCAL_FALLBACK";
}
