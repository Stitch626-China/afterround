package com.jobpilot.modules.resume.dto;

/** 分析状态与报告（契约 ResumeAnalysisStatus）；report 仅 COMPLETED 时有值。 */
public record ResumeAnalysisStatusResponse(
        Long resumeId,
        String status,
        String error,
        Integer retryCount,
        ResumeAnalysisReportResponse report) {
}
