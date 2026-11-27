package com.jobpilot.modules.prompt.service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.api.PageResult;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.modules.eval.service.EvalImpactAnalyzer;
import com.jobpilot.modules.prompt.catalog.PromptCatalog;
import com.jobpilot.modules.prompt.catalog.PromptSpec;
import com.jobpilot.modules.prompt.catalog.PromptVersionSource;
import com.jobpilot.modules.prompt.dto.PromptDetail;
import com.jobpilot.modules.prompt.dto.PromptSummary;
import com.jobpilot.modules.prompt.dto.PromptValidation;
import com.jobpilot.modules.prompt.dto.PromptVersionDetail;
import com.jobpilot.modules.prompt.dto.PromptVersionSummary;
import com.jobpilot.modules.prompt.dto.PublishPromptRequest;
import com.jobpilot.modules.prompt.dto.SavePromptDraftRequest;
import com.jobpilot.modules.prompt.entity.LlmPromptVersion;
import com.jobpilot.modules.prompt.mapper.LlmPromptVersionMapper;
import com.jobpilot.modules.prompt.service.PromptValidator.ValidationResult;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 提示词草稿 / 发布 / 回滚 / 版本历史。
 *
 * <p>发布只改 DB 并失效运行时缓存，不触发任何评测。
 */
@Service
public class PromptService {

    private static final String ACTIVE = "ACTIVE";
    private static final String DRAFT = "DRAFT";
    private static final String ARCHIVED = "ARCHIVED";

    private final PromptCatalog catalog;
    private final LlmPromptVersionMapper mapper;
    private final PromptValidator validator;
    private final PromptRegistry registry;
    private final EvalImpactAnalyzer impactAnalyzer;

    public PromptService(PromptCatalog catalog, LlmPromptVersionMapper mapper,
                         PromptValidator validator, PromptRegistry registry,
                         EvalImpactAnalyzer impactAnalyzer) {
        this.catalog = catalog;
        this.mapper = mapper;
        this.validator = validator;
        this.registry = registry;
        this.impactAnalyzer = impactAnalyzer;
    }

    /** 12 组提示词摘要，不返回全文。 */
    public List<PromptSummary> list() {
        return catalog.all().stream()
                .map(spec -> summary(spec, activeRow(spec.key()), draftRow(spec.key())))
                .toList();
    }

    /** 生效版本 + 内置默认 + 草稿 + 校验结果。 */
    public PromptDetail detail(String key) {
        PromptSpec spec = catalog.get(key);
        LlmPromptVersion activeRow = activeRow(key);
        LlmPromptVersion draftRow = draftRow(key);
        PromptSummary summary = summary(spec, activeRow, draftRow);
        PromptVersionDetail builtIn = builtInDetail(spec);
        PromptVersionDetail active = activeRow == null ? builtIn : dbDetail(activeRow);
        PromptVersionDetail draft = draftRow == null ? null : dbDetail(draftRow);
        PromptValidation validation = validation(spec, draft == null ? active : draft,
                draft != null, active);
        return new PromptDetail(summary.key(), summary.name(), summary.category(), summary.description(),
                summary.outputMode(), summary.modelScope(), summary.scene(), summary.evalScene(),
                summary.hasEvalCorpus(), summary.affectedFunctions(), summary.placeholders(),
                summary.requiredFields(), summary.activeVersion(), summary.builtinVersion(),
                summary.hasDraft(), summary.updatedAt(), active, builtIn, draft, validation,
                impactAnalyzer.suggestForPrompt(key));
    }

    /** 保存草稿：可保存但有错误时不能发布。 */
    @Transactional
    public PromptDetail saveDraft(String key, SavePromptDraftRequest request) {
        catalog.get(key);
        LlmPromptVersion draft = draftRow(key);
        String hash = PromptRegistry.hash(request.systemContent(), request.userContent());
        try {
            if (draft == null) {
                LlmPromptVersion row = new LlmPromptVersion();
                row.setUserId(1L);
                row.setPromptKey(key);
                row.setVersionNo(maxVersion(key) + 1);
                row.setSystemContent(request.systemContent());
                row.setUserContent(request.userContent());
                row.setContentHash(hash);
                row.setStatus(DRAFT);
                row.setNote(request.note());
                mapper.insert(row);
            } else {
                draft.setSystemContent(request.systemContent());
                draft.setUserContent(request.userContent());
                draft.setContentHash(hash);
                draft.setNote(request.note());
                mapper.updateById(draft);
            }
        } catch (DataIntegrityViolationException ex) {
            throw conflict();
        }
        return detail(key);
    }

    /** 丢弃草稿；没有草稿时幂等成功。 */
    @Transactional
    public void discardDraft(String key) {
        catalog.get(key);
        mapper.delete(Wrappers.<LlmPromptVersion>lambdaQuery()
                .eq(LlmPromptVersion::getPromptKey, key)
                .eq(LlmPromptVersion::getStatus, DRAFT));
    }

    /** 发布草稿：旧 ACTIVE 归档，草稿转 ACTIVE 并热更新；不触发评测。 */
    @Transactional
    public PromptDetail publish(String key, PublishPromptRequest request) {
        PromptSpec spec = catalog.get(key);
        LlmPromptVersion draft = draftRow(key);
        if (draft == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "没有可发布的草稿");
        }
        ValidationResult result = validator.validate(spec, draft.getSystemContent(), draft.getUserContent());
        if (!result.valid()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, String.join("；", result.errors()));
        }
        try {
            LlmPromptVersion active = activeRow(key);
            if (active != null) {
                active.setStatus(ARCHIVED);
                mapper.updateById(active);
            }
            draft.setStatus(ACTIVE);
            draft.setPublishedAt(OffsetDateTime.now());
            if (request != null && request.note() != null) {
                draft.setNote(request.note());
            }
            mapper.updateById(draft);
        } catch (DataIntegrityViolationException ex) {
            throw conflict();
        }
        registry.invalidate(key);
        return detail(key);
    }

    /** 版本历史只含 ACTIVE / ARCHIVED；草稿不混入。 */
    public PageResult<PromptVersionSummary> versions(String key, Integer page, Integer size) {
        catalog.get(key);
        int p = PageResult.normalizePage(page);
        int s = PageResult.normalizeSize(size);
        Page<LlmPromptVersion> result = mapper.selectPage(new Page<>(p, s),
                Wrappers.<LlmPromptVersion>lambdaQuery()
                        .eq(LlmPromptVersion::getPromptKey, key)
                        .in(LlmPromptVersion::getStatus, List.of(ACTIVE, ARCHIVED))
                        .orderByDesc(LlmPromptVersion::getVersionNo));
        return PageResult.of(result.getRecords().stream().map(this::dbSummary).toList(),
                result.getTotal(), p, s);
    }

    /** 单个历史版本详情。 */
    public PromptVersionDetail version(String key, int versionNo) {
        catalog.get(key);
        LlmPromptVersion row = findVersion(key, versionNo);
        if (DRAFT.equals(row.getStatus())) {
            throw BizException.of(ErrorCode.NOT_FOUND, "提示词版本不存在: " + versionNo);
        }
        return dbDetail(row);
    }

    /** 回滚：只切换 ACTIVE / ARCHIVED，不重跑、不触发评测。 */
    @Transactional
    public PromptDetail activate(String key, int versionNo) {
        catalog.get(key);
        LlmPromptVersion target = findVersion(key, versionNo);
        if (DRAFT.equals(target.getStatus())) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "草稿不能直接激活，请先发布");
        }
        if (ACTIVE.equals(target.getStatus())) {
            return detail(key);
        }
        try {
            LlmPromptVersion active = activeRow(key);
            if (active != null) {
                active.setStatus(ARCHIVED);
                mapper.updateById(active);
            }
            target.setStatus(ACTIVE);
            target.setPublishedAt(OffsetDateTime.now());
            mapper.updateById(target);
        } catch (DataIntegrityViolationException ex) {
            throw conflict();
        }
        registry.invalidate(key);
        return detail(key);
    }

    private LlmPromptVersion activeRow(String key) {
        return mapper.selectOne(Wrappers.<LlmPromptVersion>lambdaQuery()
                .eq(LlmPromptVersion::getPromptKey, key)
                .eq(LlmPromptVersion::getStatus, ACTIVE));
    }

    private LlmPromptVersion draftRow(String key) {
        return mapper.selectOne(Wrappers.<LlmPromptVersion>lambdaQuery()
                .eq(LlmPromptVersion::getPromptKey, key)
                .eq(LlmPromptVersion::getStatus, DRAFT));
    }

    private LlmPromptVersion findVersion(String key, int versionNo) {
        if (versionNo < 1) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "versionNo 必须大于 0");
        }
        LlmPromptVersion row = mapper.selectOne(Wrappers.<LlmPromptVersion>lambdaQuery()
                .eq(LlmPromptVersion::getPromptKey, key)
                .eq(LlmPromptVersion::getVersionNo, versionNo));
        if (row == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "提示词版本不存在: " + versionNo);
        }
        return row;
    }

    private int maxVersion(String key) {
        LlmPromptVersion latest = mapper.selectOne(Wrappers.<LlmPromptVersion>lambdaQuery()
                .eq(LlmPromptVersion::getPromptKey, key)
                .orderByDesc(LlmPromptVersion::getVersionNo)
                .last("limit 1"));
        return latest == null ? 0 : latest.getVersionNo();
    }

    private PromptSummary summary(PromptSpec spec, LlmPromptVersion active, LlmPromptVersion draft) {
        PromptVersionSummary activeSummary = active == null ? builtInSummary(spec) : dbSummary(active);
        return new PromptSummary(spec.key(), spec.name(), spec.category(), spec.description(),
                spec.outputMode(), spec.modelScope(),
                spec.scene() == null ? null : spec.scene().name(), spec.evalScene(),
                spec.evalScene() != null, spec.affectedFunctions(), spec.placeholders(),
                spec.requiredFields(), activeSummary, spec.builtinVersion(), draft != null,
                active == null ? null : active.getPublishedAt());
    }

    private PromptVersionSummary builtInSummary(PromptSpec spec) {
        return new PromptVersionSummary(0, PromptVersionSource.BUILTIN, ACTIVE,
                registry.builtin(spec.key()).contentHash(), null, null, null);
    }

    private PromptVersionSummary dbSummary(LlmPromptVersion row) {
        return new PromptVersionSummary(row.getVersionNo(), PromptVersionSource.DB, row.getStatus(),
                row.getContentHash(), row.getNote(), row.getCreatedAt(), row.getPublishedAt());
    }

    private PromptVersionDetail builtInDetail(PromptSpec spec) {
        PromptTemplate template = registry.builtin(spec.key());
        return new PromptVersionDetail(0, PromptVersionSource.BUILTIN, ACTIVE, template.contentHash(),
                null, null, null, template.system(), template.user());
    }

    private PromptVersionDetail dbDetail(LlmPromptVersion row) {
        return new PromptVersionDetail(row.getVersionNo(), PromptVersionSource.DB, row.getStatus(),
                row.getContentHash(), row.getNote(), row.getCreatedAt(), row.getPublishedAt(),
                row.getSystemContent(), row.getUserContent());
    }

    private PromptValidation validation(PromptSpec spec, PromptVersionDetail target, boolean hasDraft,
                                        PromptVersionDetail active) {
        ValidationResult result = validator.validate(spec, target.systemContent(), target.userContent());
        List<String> warnings = new ArrayList<>(result.warnings());
        if (hasDraft) {
            warnings.add("存在未发布草稿；发布后才会生效");
            if (target.contentHash().equals(active.contentHash())) {
                warnings.add("草稿内容与当前生效版本相同");
            }
        }
        return new PromptValidation(result.valid(), result.errors(), List.copyOf(warnings));
    }

    private static BizException conflict() {
        return BizException.of(ErrorCode.PROMPT_VERSION_CONFLICT, "提示词版本冲突，请刷新后重试");
    }
}
