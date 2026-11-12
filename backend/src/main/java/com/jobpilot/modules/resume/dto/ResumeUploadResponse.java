package com.jobpilot.modules.resume.dto;

/** 上传结果（契约 ResumeUpload）；duplicated=true 表示命中 SHA-256 去重。 */
public record ResumeUploadResponse(ResumeListItemResponse resume, boolean duplicated) {
}
