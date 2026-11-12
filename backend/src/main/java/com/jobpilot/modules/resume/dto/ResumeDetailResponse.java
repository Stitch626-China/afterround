package com.jobpilot.modules.resume.dto;

import java.time.OffsetDateTime;

import com.jobpilot.modules.resume.entity.Resume;

/** 简历详情（契约 ResumeDetail）：列表项字段 + 哈希 / 解析文本 / 失败信息。 */
public record ResumeDetailResponse(
        Long id,
        String fileName,
        String fileType,
        Long fileSize,
        String analyzeStatus,
        Integer overallScore,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String fileHash,
        String resumeText,
        String analyzeError,
        Integer retryCount) {

    public static ResumeDetailResponse from(Resume resume, Integer overallScore) {
        return new ResumeDetailResponse(resume.getId(), resume.getFileName(), resume.getFileType(),
                resume.getFileSize(), resume.getAnalyzeStatus(), overallScore, resume.getCreatedAt(),
                resume.getUpdatedAt(), resume.getFileHash(), resume.getResumeText(),
                resume.getAnalyzeError(), resume.getRetryCount());
    }
}
