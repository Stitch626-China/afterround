package com.jobpilot.modules.resume.entity;

import java.time.OffsetDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 简历主记录；文件按 SHA-256 去重，解析文本落库。 */
@Data
@TableName("resumes")
public class Resume {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String fileHash;
    private String fileName;
    private String fileType;
    private Long fileSize;
    private String storageKey;
    private String resumeText;
    private String analyzeStatus;
    private String analyzeError;
    private Integer retryCount;
    /** 用户填写的当前月薪（元/月），优先于 LLM 提取值。 */
    private Integer currentSalary;
    /** 用户填写的期望月薪（元/月）。 */
    private Integer expectedSalary;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
