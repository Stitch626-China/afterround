package com.jobpilot.modules.resume.service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.api.PageResult;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.common.text.TextCleaner;
import com.jobpilot.infrastructure.storage.FileStorage;
import com.jobpilot.modules.resume.dto.ResumeDetailResponse;
import com.jobpilot.modules.resume.dto.ResumeListItemResponse;
import com.jobpilot.modules.resume.dto.ResumeUploadResponse;
import com.jobpilot.modules.resume.entity.Resume;
import com.jobpilot.modules.resume.entity.ResumeAnalysis;
import com.jobpilot.modules.resume.mapper.ResumeAnalysisMapper;
import com.jobpilot.modules.resume.mapper.ResumeMapper;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 简历中心（FR1.1~FR1.4 / FR1.8 / FR1.9）：上传、Tika 解析清洗、SHA-256 去重、分页、详情、删除。
 */
@Service
public class ResumeService {

    private static final Logger log = LoggerFactory.getLogger(ResumeService.class);
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx", "doc", "txt");
    private static final int MAX_PARSED_CHARS = 2_000_000;

    private final ResumeMapper resumeMapper;
    private final ResumeAnalysisMapper analysisMapper;
    private final FileStorage storage;
    private final ResumeAnalysisService analysisService;

    public ResumeService(ResumeMapper resumeMapper, ResumeAnalysisMapper analysisMapper,
                         FileStorage storage, ResumeAnalysisService analysisService) {
        this.resumeMapper = resumeMapper;
        this.analysisMapper = analysisMapper;
        this.storage = storage;
        this.analysisService = analysisService;
    }

    public PageResult<ResumeListItemResponse> page(Integer page, Integer size) {
        int current = PageResult.normalizePage(page);
        int pageSize = PageResult.normalizeSize(size);
        Page<Resume> result = resumeMapper.selectPage(new Page<>(current, pageSize),
                Wrappers.<Resume>lambdaQuery()
                        .orderByDesc(Resume::getCreatedAt).orderByDesc(Resume::getId));
        List<Resume> records = result.getRecords();
        Map<Long, Integer> scores = scoresOf(records);
        return PageResult.of(records.stream()
                        .map(resume -> ResumeListItemResponse.from(resume, scores.get(resume.getId())))
                        .toList(),
                result.getTotal(), current, pageSize);
    }

    public ResumeDetailResponse detail(Long resumeId) {
        Resume resume = require(resumeId);
        ResumeAnalysis analysis = analysisMapper.selectOne(Wrappers.<ResumeAnalysis>lambdaQuery()
                .eq(ResumeAnalysis::getResumeId, resumeId));
        Integer overall = analysis == null || analysis.getOverallScore() == null
                ? null : analysis.getOverallScore();
        return ResumeDetailResponse.from(resume, overall);
    }

    /** 文件与粘贴文本二选一；内容相同直接返回已有记录，不重复触发分析。 */
    public ResumeUploadResponse upload(MultipartFile file, String text) {
        boolean hasFile = file != null && !file.isEmpty();
        boolean hasText = text != null && !text.isBlank();
        if (!hasFile && !hasText) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "file 与 text 至少提供一个");
        }
        byte[] bytes;
        String fileName = null;
        String fileType;
        String storageKey = null;
        String resumeText;
        try {
            if (hasFile) {
                if (file.getSize() > MAX_BYTES) {
                    throw BizException.of(ErrorCode.PAYLOAD_TOO_LARGE);
                }
                fileName = file.getOriginalFilename();
                fileType = extensionOf(fileName);
                bytes = file.getBytes();
                resumeText = parse(bytes);
                if (resumeText.isBlank()) {
                    throw BizException.of(ErrorCode.BAD_REQUEST, "简历解析结果为空");
                }
                storageKey = "resumes/" + sha256(bytes) + "." + fileType.toLowerCase();
            } else {
                bytes = text.getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_BYTES) {
                    throw BizException.of(ErrorCode.PAYLOAD_TOO_LARGE);
                }
                fileType = "TXT";
                resumeText = TextCleaner.clean(text);
            }
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "文件读取失败: " + ex.getMessage());
        }

        String fileHash = sha256(bytes);
        Resume existing = resumeMapper.selectOne(Wrappers.<Resume>lambdaQuery()
                .eq(Resume::getFileHash, fileHash));
        if (existing != null) {
            return new ResumeUploadResponse(listItem(existing), true);
        }
        if (storageKey != null) {
            storage.save(storageKey, bytes);
        }

        Resume entity = new Resume();
        entity.setUserId(1L);
        entity.setFileHash(fileHash);
        entity.setFileName(fileName);
        entity.setFileType(fileType.toUpperCase());
        entity.setFileSize((long) bytes.length);
        entity.setStorageKey(storageKey);
        entity.setResumeText(resumeText);
        entity.setAnalyzeStatus("PENDING");
        entity.setRetryCount(0);
        try {
            resumeMapper.insert(entity);
        } catch (DuplicateKeyException ex) {
            Resume duplicated = resumeMapper.selectOne(Wrappers.<Resume>lambdaQuery()
                    .eq(Resume::getFileHash, fileHash));
            return new ResumeUploadResponse(listItem(duplicated), true);
        }

        Resume saved = resumeMapper.selectById(entity.getId());
        analysisService.autoTrigger(saved.getId());
        return new ResumeUploadResponse(listItem(saved), false);
    }

    /** 硬删除：清理磁盘文件 + 分析结果 + 主记录。 */
    public void delete(Long resumeId) {
        Resume resume = require(resumeId);
        if (resume.getStorageKey() != null) {
            try {
                storage.delete(resume.getStorageKey());
            } catch (Exception ex) {
                log.warn("删除简历文件失败 resumeId={} key={}", resumeId, resume.getStorageKey(), ex);
            }
        }
        analysisMapper.delete(Wrappers.<ResumeAnalysis>lambdaQuery()
                .eq(ResumeAnalysis::getResumeId, resumeId));
        resumeMapper.deleteById(resumeId);
    }

    public Resume require(Long resumeId) {
        Resume resume = resumeMapper.selectById(resumeId);
        if (resume == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "简历不存在: " + resumeId);
        }
        return resume;
    }

    private ResumeListItemResponse listItem(Resume resume) {
        ResumeAnalysis analysis = analysisMapper.selectOne(Wrappers.<ResumeAnalysis>lambdaQuery()
                .eq(ResumeAnalysis::getResumeId, resume.getId()));
        return ResumeListItemResponse.from(resume,
                analysis == null ? null : analysis.getOverallScore());
    }

    private Map<Long, Integer> scoresOf(List<Resume> resumes) {
        if (resumes.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = resumes.stream().map(Resume::getId).toList();
        return analysisMapper.selectList(Wrappers.<ResumeAnalysis>lambdaQuery()
                        .in(ResumeAnalysis::getResumeId, ids)
                        .isNotNull(ResumeAnalysis::getOverallScore))
                .stream().collect(Collectors.toMap(ResumeAnalysis::getResumeId, ResumeAnalysis::getOverallScore));
    }

    private static String parse(byte[] bytes) {
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            Tika tika = new Tika();
            tika.setMaxStringLength(MAX_PARSED_CHARS);
            return TextCleaner.clean(tika.parseToString(in));
        } catch (Exception ex) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "文件解析失败，请确认格式为 PDF/DOCX/DOC/TXT");
        }
    }

    private static String extensionOf(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            throw BizException.of(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "缺少文件扩展名");
        }
        String extension = fileName.substring(dot + 1).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw BizException.of(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "仅支持 PDF/DOCX/DOC/TXT");
        }
        return extension;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 计算失败", ex);
        }
    }
}
