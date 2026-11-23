package com.jobpilot.modules.job.service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.api.PageResult;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.common.llm.ChatMessage;
import com.jobpilot.common.llm.ChatResult;
import com.jobpilot.common.llm.LlmClient;
import com.jobpilot.common.llm.StructuredOutputInvoker;
import com.jobpilot.common.text.TextCleaner;
import com.jobpilot.infrastructure.llm.LlmCallLogger;
import com.jobpilot.infrastructure.llm.ProviderClientRegistry;
import com.jobpilot.infrastructure.storage.FileStorage;
import com.jobpilot.modules.company.entity.Company;
import com.jobpilot.modules.company.mapper.CompanyMapper;
import com.jobpilot.modules.job.ai.AiJobPositionParse;
import com.jobpilot.modules.job.dto.BatchDeleteJobPositionsRequest;
import com.jobpilot.modules.job.dto.JobPositionDetailResponse;
import com.jobpilot.modules.job.dto.JobPositionListItemResponse;
import com.jobpilot.modules.job.dto.UpdateJobPositionRequest;
import com.jobpilot.modules.job.entity.JobPosition;
import com.jobpilot.modules.job.mapper.JobPositionMapper;
import com.jobpilot.modules.job.prompt.JobPositionPromptFactory;
import com.jobpilot.modules.llm.service.LlmSceneService;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 岗位库（FR2.1 / FR2.2 / FR2.5 / FR2.6）：JD 导入 → 异步结构化解析 → 列表/详情/修正/删除。
 *
 * <p>解析走全局默认模型（JD 解析不在 LlmScene 枚举内），状态四件套与简历分析一致：
 * 首次 + 自动重试 ≤ 3 次，失败落 parseError；公司按名称全等自动关联，未命中则新建，不做模糊合并。
 */
@Service
public class JobPositionService {

    private static final Logger log = LoggerFactory.getLogger(JobPositionService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx", "doc", "txt");
    private static final int MAX_RETRIES = 3;
    private static final int MAX_PARSED_CHARS = 500_000;

    private final JobPositionMapper jobMapper;
    private final CompanyMapper companyMapper;
    private final LlmSceneService sceneService;
    private final ProviderClientRegistry registry;
    private final LlmCallLogger callLogger;
    private final JobPositionPromptFactory prompts;
    private final FileStorage storage;
    private final ThreadPoolTaskExecutor executor;

    public JobPositionService(JobPositionMapper jobMapper, CompanyMapper companyMapper,
                              LlmSceneService sceneService, ProviderClientRegistry registry,
                              LlmCallLogger callLogger, JobPositionPromptFactory prompts,
                              FileStorage storage,
                              @Qualifier("jobpilotTaskExecutor") ThreadPoolTaskExecutor executor) {
        this.jobMapper = jobMapper;
        this.companyMapper = companyMapper;
        this.sceneService = sceneService;
        this.registry = registry;
        this.callLogger = callLogger;
        this.prompts = prompts;
        this.storage = storage;
        this.executor = executor;
    }

    public PageResult<JobPositionListItemResponse> page(Integer page, Integer size, String status,
                                                        String city, String keyword) {
        int current = PageResult.normalizePage(page);
        int pageSize = PageResult.normalizeSize(size);
        String kw = blank(keyword) ? null : keyword.trim();
        List<Long> companyIds = companyIdsMatching(kw);
        Page<JobPosition> result = jobMapper.selectPage(new Page<>(current, pageSize),
                Wrappers.<JobPosition>lambdaQuery()
                        .eq(!blank(status), JobPosition::getStatus, status)
                        .eq(!blank(city), JobPosition::getCity, city == null ? null : city.trim())
                        .and(kw != null, w -> w.like(JobPosition::getTitle, kw)
                                .or(!companyIds.isEmpty(), x -> x.in(JobPosition::getCompanyId, companyIds)))
                        .orderByDesc(JobPosition::getCreatedAt).orderByDesc(JobPosition::getId));
        Map<Long, String> names = companyNames(result.getRecords());
        return PageResult.of(result.getRecords().stream()
                        // 未关联公司的岗位（如刚粘贴导入、解析未完成）companyId 为 null，
                        // 而 companyNames 在无关联时返回不可变空 Map，get(null) 会抛 NPE
                        .map(job -> toListItem(job, job.getCompanyId() == null ? null : names.get(job.getCompanyId())))
                        .toList(),
                result.getTotal(), current, pageSize);
    }

    /** 文件与粘贴文本二选一；创建后立即返回 PENDING 并静默触发异步解析。 */
    public JobPositionListItemResponse importJd(MultipartFile file, String text) {
        boolean hasFile = file != null && !file.isEmpty();
        boolean hasText = text != null && !text.isBlank();
        if (!hasFile && !hasText) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "file 与 text 至少提供一个");
        }
        String fileName = null;
        String storageKey = null;
        String rawText;
        byte[] bytes;
        try {
            if (hasFile) {
                if (file.getSize() > MAX_BYTES) {
                    throw BizException.of(ErrorCode.PAYLOAD_TOO_LARGE);
                }
                fileName = file.getOriginalFilename();
                String extension = extensionOf(fileName);
                bytes = file.getBytes();
                rawText = parse(bytes);
                if (rawText.isBlank()) {
                    throw BizException.of(ErrorCode.BAD_REQUEST, "JD 解析结果为空");
                }
                storageKey = "job-positions/" + sha256(bytes) + "." + extension;
            } else {
                bytes = text.getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_BYTES) {
                    throw BizException.of(ErrorCode.PAYLOAD_TOO_LARGE);
                }
                rawText = TextCleaner.clean(text);
                if (rawText.isBlank()) {
                    throw BizException.of(ErrorCode.BAD_REQUEST, "JD 内容不能为空");
                }
            }
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "文件读取失败: " + ex.getMessage());
        }
        if (storageKey != null) {
            storage.save(storageKey, bytes);
        }
        JobPosition entity = new JobPosition();
        entity.setUserId(1L);
        entity.setSourceType(hasFile ? "FILE" : "TEXT");
        entity.setFileName(fileName);
        entity.setStorageKey(storageKey);
        entity.setRawText(rawText);
        entity.setMustHaveSkills("[]");
        entity.setNiceToHaveSkills("[]");
        entity.setStatus("SAVED");
        entity.setParseStatus("PENDING");
        entity.setRetryCount(0);
        jobMapper.insert(entity);

        autoTrigger(entity.getId());
        return toListItem(jobMapper.selectById(entity.getId()), null);
    }

    public JobPositionDetailResponse detail(Long jobPositionId) {
        JobPosition entity = require(jobPositionId);
        return toDetail(entity, companyName(entity.getCompanyId()));
    }

    /** 用户修正：解析结果可覆盖，parseStatus 不变；companyId 显式 null 表示解除关联。 */
    public JobPositionListItemResponse update(Long jobPositionId, UpdateJobPositionRequest request) {
        JobPosition entity = require(jobPositionId);
        if (request.title() != null) {
            entity.setTitle(truncate(request.title().trim(), 128));
        }
        JsonNode companyId = request.companyId();
        if (companyId != null) {
            if (companyId.isNull()) {
                entity.setCompanyId(null);
            } else {
                Long id = companyId.asLong();
                if (companyMapper.selectById(id) == null) {
                    throw BizException.of(ErrorCode.NOT_FOUND, "公司不存在: " + id);
                }
                entity.setCompanyId(id);
            }
        }
        if (request.city() != null) {
            entity.setCity(truncate(request.city().trim(), 64));
        }
        Integer newMin = request.salaryMin() != null ? request.salaryMin() : entity.getSalaryMin();
        Integer newMax = request.salaryMax() != null ? request.salaryMax() : entity.getSalaryMax();
        if (newMin != null && newMax != null && newMin > newMax) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "薪资下限不能高于上限");
        }
        if (request.salaryMin() != null) {
            entity.setSalaryMin(request.salaryMin());
        }
        if (request.salaryMax() != null) {
            entity.setSalaryMax(request.salaryMax());
        }
        if (request.experience() != null) {
            entity.setExperience(truncate(request.experience(), 64));
        }
        if (request.education() != null) {
            entity.setEducation(truncate(request.education(), 64));
        }
        if (request.mustHaveSkills() != null) {
            entity.setMustHaveSkills(skillsJson(request.mustHaveSkills()));
        }
        if (request.niceToHaveSkills() != null) {
            entity.setNiceToHaveSkills(skillsJson(request.niceToHaveSkills()));
        }
        if (request.responsibility() != null) {
            entity.setResponsibility(truncate(request.responsibility(), 5000));
        }
        if (request.status() != null) {
            entity.setStatus(request.status());
        }
        if (request.remark() != null) {
            String remark = request.remark().trim();
            entity.setRemark(remark.isEmpty() ? null : truncate(remark, 200));
        }
        entity.setUpdatedAt(OffsetDateTime.now());
        jobMapper.updateById(entity);
        return toListItem(entity, companyName(entity.getCompanyId()));
    }

    /** 硬删除 + 磁盘文件清理；匹配结果由数据库外键级联删除。 */
    public void delete(Long jobPositionId) {
        JobPosition entity = require(jobPositionId);
        deleteFile(entity);
        jobMapper.deleteById(jobPositionId);
    }

    /** 批量删除：不存在的 id 忽略，单条文件清理失败不阻断（避免无法删除的僵局）。 */
    public void batchDelete(BatchDeleteJobPositionsRequest request) {
        List<Long> ids = request.ids().stream().distinct().toList();
        List<JobPosition> entities = jobMapper.selectByIds(ids);
        entities.forEach(this::deleteFile);
        if (!entities.isEmpty()) {
            jobMapper.deleteByIds(entities.stream().map(JobPosition::getId).toList());
        }
    }

    /** 手动触发 / 重试解析：PROCESSING 时 409；重试重置 retryCount。 */
    public JobPositionListItemResponse retryParse(Long jobPositionId) {
        require(jobPositionId);
        int updated = jobMapper.update(null, Wrappers.<JobPosition>lambdaUpdate()
                .eq(JobPosition::getId, jobPositionId)
                .ne(JobPosition::getParseStatus, "PROCESSING")
                .set(JobPosition::getParseStatus, "PROCESSING")
                .set(JobPosition::getRetryCount, 0)
                .set(JobPosition::getParseError, null)
                .set(JobPosition::getUpdatedAt, OffsetDateTime.now()));
        if (updated == 0) {
            throw BizException.of(ErrorCode.TASK_IN_PROGRESS);
        }
        submit(jobPositionId);
        JobPosition saved = jobMapper.selectById(jobPositionId);
        return toListItem(saved, companyName(saved.getCompanyId()));
    }

    /** 导入后的静默触发：仅在已配置全局默认且记录仍为 PENDING 时执行。 */
    private void autoTrigger(Long jobPositionId) {
        try {
            sceneService.resolveGlobal();
            int updated = jobMapper.update(null, Wrappers.<JobPosition>lambdaUpdate()
                    .eq(JobPosition::getId, jobPositionId)
                    .eq(JobPosition::getParseStatus, "PENDING")
                    .set(JobPosition::getParseStatus, "PROCESSING")
                    .set(JobPosition::getUpdatedAt, OffsetDateTime.now()));
            if (updated > 0) {
                submit(jobPositionId);
            }
        } catch (Exception ex) {
            log.info("JD 自动解析未触发 jobPositionId={}：{}", jobPositionId, ex.getMessage());
        }
    }

    private void submit(Long jobPositionId) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        try {
            executor.execute(() -> {
                if (context != null) {
                    MDC.setContextMap(context);
                }
                try {
                    parse(jobPositionId);
                } finally {
                    MDC.clear();
                }
            });
        } catch (RuntimeException ex) {
            resetToPending(jobPositionId, "解析任务队列已满，请稍后重试");
            log.warn("JD 解析任务入队失败 jobPositionId={}", jobPositionId, ex);
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "解析任务队列已满，请稍后重试");
        }
    }

    /** 异步 worker：首次 + 最多 3 次重试；成功后覆盖结构化字段并关联公司。 */
    private void parse(Long jobPositionId) {
        JobPosition job = jobMapper.selectById(jobPositionId);
        if (job == null || !"PROCESSING".equals(job.getParseStatus())) {
            return;
        }
        LlmSceneService.SceneModel target;
        LlmClient client;
        try {
            target = sceneService.resolveGlobal();
            client = registry.get(target.providerKey());
        } catch (Exception ex) {
            finish(jobPositionId, "FAILED", truncate(ex.getMessage()), 0);
            return;
        }
        StructuredOutputInvoker invoker = new StructuredOutputInvoker(client);
        List<ChatMessage> messages = List.of(
                ChatMessage.system(prompts.system()),
                ChatMessage.user(prompts.user(job.getRawText())));

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (jobMapper.selectById(jobPositionId) == null) {
                log.info("岗位 {} 已删除，终止解析", jobPositionId);
                return;
            }
            long start = System.nanoTime();
            try {
                StructuredOutputInvoker.Parsed<AiJobPositionParse> parsed =
                        invoker.invokeWithUsage(target.model(), messages, AiJobPositionParse.class);
                applyParsed(jobPositionId, parsed.value(), target);
                ChatResult usage = parsed.result();
                callLogger.log("JD_PARSE", target.providerKey(), target.model(),
                        usage.promptTokens(), usage.completionTokens(), usage.totalTokens(),
                        elapsed(start), true, null);
                return;
            } catch (Exception ex) {
                if (jobMapper.selectById(jobPositionId) == null) {
                    log.info("岗位 {} 已删除，终止解析", jobPositionId);
                    return;
                }
                int retries = Math.min(attempt + 1, MAX_RETRIES);
                finish(jobPositionId, attempt == MAX_RETRIES ? "FAILED" : "PROCESSING",
                        truncate(ex.getMessage()), retries);
                callLogger.log("JD_PARSE", target.providerKey(), target.model(),
                        null, null, null, elapsed(start), false, ex.getMessage());
                log.warn("JD 解析失败 jobPositionId={} 第 {} 次: {}", jobPositionId, attempt + 1, ex.getMessage());
            }
        }
    }

    /** 本地兜底：技能去重去空、长度截断、薪资区间纠正；公司按全等名关联或新建。 */
    private void applyParsed(Long jobPositionId, AiJobPositionParse ai, LlmSceneService.SceneModel target) {
        JobPosition entity = jobMapper.selectById(jobPositionId);
        if (entity == null) {
            return;
        }
        entity.setTitle(truncate(blank(ai.title()) ? null : ai.title().trim(), 128));
        entity.setCity(truncate(blank(ai.city()) ? null : ai.city().trim(), 64));
        Integer min = salary(ai.salaryMin());
        Integer max = salary(ai.salaryMax());
        if (min != null && max != null && min > max) {
            Integer swap = min;
            min = max;
            max = swap;
        }
        entity.setSalaryMin(min);
        entity.setSalaryMax(max);
        entity.setExperience(truncate(blank(ai.experience()) ? null : ai.experience().trim(), 64));
        entity.setEducation(truncate(blank(ai.education()) ? null : ai.education().trim(), 64));
        entity.setMustHaveSkills(skillsJson(ai.mustHaveSkills()));
        entity.setNiceToHaveSkills(skillsJson(ai.niceToHaveSkills()));
        entity.setResponsibility(truncate(blank(ai.responsibility()) ? null : ai.responsibility().trim(), 5000));
        entity.setCompanyId(resolveCompany(ai.companyName()));
        entity.setParseStatus("COMPLETED");
        entity.setParseError(null);
        entity.setLlmProvider(target.providerKey());
        entity.setLlmModel(target.model());
        entity.setUpdatedAt(OffsetDateTime.now());
        jobMapper.updateById(entity);
    }

    /** 全等名命中则复用，否则新建；并发唯一冲突时回查已有记录。 */
    private Long resolveCompany(String name) {
        if (blank(name)) {
            return null;
        }
        String trimmed = truncate(name.trim(), 128);
        Company existing = companyMapper.selectOne(Wrappers.<Company>lambdaQuery()
                .eq(Company::getName, trimmed));
        if (existing != null) {
            return existing.getId();
        }
        Company company = new Company();
        company.setUserId(1L);
        company.setName(trimmed);
        company.setTags("[]");
        try {
            companyMapper.insert(company);
            return company.getId();
        } catch (DuplicateKeyException ex) {
            Company duplicated = companyMapper.selectOne(Wrappers.<Company>lambdaQuery()
                    .eq(Company::getName, trimmed));
            return duplicated == null ? null : duplicated.getId();
        }
    }

    private void finish(Long jobPositionId, String parseStatus, String error, int retryCount) {
        jobMapper.update(null, Wrappers.<JobPosition>lambdaUpdate()
                .eq(JobPosition::getId, jobPositionId)
                .set(JobPosition::getParseStatus, parseStatus)
                .set(JobPosition::getParseError, truncate(error))
                .set(JobPosition::getRetryCount, retryCount)
                .set(JobPosition::getUpdatedAt, OffsetDateTime.now()));
    }

    /** 仅把仍在 PROCESSING 的记录回退为 PENDING，避免覆盖已完成结果。 */
    private void resetToPending(Long jobPositionId, String error) {
        jobMapper.update(null, Wrappers.<JobPosition>lambdaUpdate()
                .eq(JobPosition::getId, jobPositionId)
                .eq(JobPosition::getParseStatus, "PROCESSING")
                .set(JobPosition::getParseStatus, "PENDING")
                .set(JobPosition::getParseError, truncate(error))
                .set(JobPosition::getUpdatedAt, OffsetDateTime.now()));
    }

    private void deleteFile(JobPosition job) {
        if (job.getStorageKey() == null) {
            return;
        }
        try {
            storage.delete(job.getStorageKey());
        } catch (Exception ex) {
            // 文件清理失败不阻断删除：raw_text 已落库，残留文件不影响业务可用性
            log.warn("删除 JD 文件失败 jobPositionId={} key={}", job.getId(), job.getStorageKey(), ex);
        }
    }

    public JobPosition require(Long jobPositionId) {
        JobPosition entity = jobMapper.selectById(jobPositionId);
        if (entity == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "岗位不存在: " + jobPositionId);
        }
        return entity;
    }

    private List<Long> companyIdsMatching(String keyword) {
        if (keyword == null) {
            return List.of();
        }
        return companyMapper.selectList(Wrappers.<Company>lambdaQuery()
                        .like(Company::getName, keyword))
                .stream().map(Company::getId).toList();
    }

    private Map<Long, String> companyNames(List<JobPosition> jobs) {
        List<Long> ids = jobs.stream().map(JobPosition::getCompanyId).filter(java.util.Objects::nonNull)
                .distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return companyMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(Company::getId, Company::getName));
    }

    private String companyName(Long companyId) {
        if (companyId == null) {
            return null;
        }
        Company company = companyMapper.selectById(companyId);
        return company == null ? null : company.getName();
    }

    private JobPositionListItemResponse toListItem(JobPosition job, String companyName) {
        return new JobPositionListItemResponse(job.getId(), job.getTitle(), job.getCompanyId(), companyName,
                job.getCity(), job.getSalaryMin(), job.getSalaryMax(), job.getExperience(), job.getEducation(),
                job.getStatus(), job.getRemark(), job.getParseStatus(), job.getRetryCount(),
                job.getCreatedAt(), job.getUpdatedAt());
    }

    private JobPositionDetailResponse toDetail(JobPosition job, String companyName) {
        return new JobPositionDetailResponse(job.getId(), job.getTitle(), job.getCompanyId(), companyName,
                job.getCity(), job.getSalaryMin(), job.getSalaryMax(), job.getExperience(), job.getEducation(),
                job.getStatus(), job.getRemark(), job.getParseStatus(), job.getRetryCount(),
                job.getCreatedAt(), job.getUpdatedAt(),
                job.getSourceType(), job.getFileName(), job.getRawText(),
                skills(job.getMustHaveSkills()), skills(job.getNiceToHaveSkills()),
                job.getResponsibility(), job.getParseError(), job.getLlmProvider(), job.getLlmModel());
    }

    /** 技能归一：去空、去重（保序）、单条截断；最多保留 50 项。 */
    static String skillsJson(List<String> skills) {
        if (skills == null) {
            return "[]";
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String skill : skills) {
            if (skill == null || skill.isBlank()) {
                continue;
            }
            unique.add(truncate(skill.trim(), 64));
            if (unique.size() >= 50) {
                break;
            }
        }
        try {
            return MAPPER.writeValueAsString(new ArrayList<>(unique));
        } catch (Exception ex) {
            return "[]";
        }
    }

    private static List<String> skills(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static Integer salary(Integer value) {
        if (value == null || value < 0) {
            return null;
        }
        return Math.min(value, 1_000_000);
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

    private static String parse(byte[] bytes) {
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            Tika tika = new Tika();
            tika.setMaxStringLength(MAX_PARSED_CHARS);
            return TextCleaner.clean(tika.parseToString(in));
        } catch (Exception ex) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "文件解析失败，请确认格式为 PDF/DOCX/DOC/TXT");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 计算失败", ex);
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** 错误信息统一截断到 500 字（契约 parseError 上限）。 */
    private static String truncate(String text) {
        return truncate(text, 500);
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
