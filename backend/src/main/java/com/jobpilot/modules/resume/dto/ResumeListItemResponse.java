package com.jobpilot.modules.resume.dto;

import java.time.OffsetDateTime;

import com.jobpilot.modules.resume.entity.Resume;

/** 简历列表项（契约 ResumeListItem）。 */
public record ResumeListItemResponse(
        Long id,
        String fileName,
        String fileType,
        Long fileSize,
        String analyzeStatus,
        Integer overallScore,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static ResumeListItemResponse from(Resume resume, Integer overallScore) {
        return new ResumeListItemResponse(resume.getId(), resume.getFileName(), resume.getFileType(),
                resume.getFileSize(), resume.getAnalyzeStatus(), overallScore,
                resume.getCreatedAt(), resume.getUpdatedAt());
    }
}
