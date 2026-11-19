package com.jobpilot.modules.offer.dto;

/** 分析请求：联网搜索默认关闭（FR6.4）。 */
public record AnalyzeOfferRequest(Boolean webSearch) {
}
