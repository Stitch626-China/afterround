package com.jobpilot.modules.review.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** 复盘详情（契约 ReviewSessionDetail）：列表字段 + 转写、分析、错误与音频文件名。 */
public record ReviewSessionDetailResponse(
        Long id,
        Long interviewEventId,
        Long applicationId,
        String companyName,
        String jobTitle,
        Integer roundNo,
        String roundType,
        String inputType,
        String status,
        String asrStatus,
        String analyzeStatus,
        int asrRetryCount,
        int analyzeRetryCount,
        Integer audioDurationSeconds,
        String matchSnippet,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String transcript,
        List<ReviewTranscriptSegmentResponse> transcriptSegments,
        ReviewAnalysisResponse analysis,
        boolean confirmed,
        String asrError,
        String analyzeError,
        String audioFileName) {
}
