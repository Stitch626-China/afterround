package com.jobpilot.modules.resume.dto;

import java.util.List;

/** 简历分析报告（契约 ResumeAnalysisReport）；overallScore 由本地按权重计算。 */
public record ResumeAnalysisReportResponse(
        Integer overallScore,
        List<AnalysisDimensionResponse> dimensions,
        ResumeBasicInfoResponse basicInfo,
        List<QuoteItemResponse> strengths,
        List<QuoteItemResponse> weaknesses,
        List<SuggestionItemResponse> suggestions,
        List<CareerDirectionResponse> careerDirections,
        String llmProvider,
        String llmModel) {
}
