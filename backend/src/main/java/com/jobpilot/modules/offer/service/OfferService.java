package com.jobpilot.modules.offer.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.api.PageResult;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.common.llm.ChatMessage;
import com.jobpilot.common.llm.ChatResult;
import com.jobpilot.common.llm.LlmClient;
import com.jobpilot.common.llm.StructuredOutputInvoker;
import com.jobpilot.infrastructure.llm.LlmCallLogger;
import com.jobpilot.infrastructure.llm.ProviderClientRegistry;
import com.jobpilot.modules.application.entity.JobApplication;
import com.jobpilot.modules.application.mapper.JobApplicationMapper;
import com.jobpilot.modules.company.entity.Company;
import com.jobpilot.modules.company.mapper.CompanyMapper;
import com.jobpilot.modules.job.entity.JobPosition;
import com.jobpilot.modules.job.mapper.JobPositionMapper;
import com.jobpilot.modules.llm.dto.LlmScene;
import com.jobpilot.modules.llm.service.LlmSceneService;
import com.jobpilot.modules.offer.ai.AiOfferAnalysis;
import com.jobpilot.modules.offer.ai.AiOfferExtraction;
import com.jobpilot.modules.offer.dto.AnalyzeOfferRequest;
import com.jobpilot.modules.offer.dto.CreateOfferRequest;
import com.jobpilot.modules.offer.dto.OfferAnalysis;
import com.jobpilot.modules.offer.dto.OfferComparison;
import com.jobpilot.modules.offer.dto.OfferComparisonColumn;
import com.jobpilot.modules.offer.dto.OfferComparisonRow;
import com.jobpilot.modules.offer.dto.OfferDetail;
import com.jobpilot.modules.offer.dto.OfferExtraction;
import com.jobpilot.modules.offer.dto.UpdateOfferRequest;
import com.jobpilot.modules.offer.entity.Offer;
import com.jobpilot.modules.offer.mapper.OfferMapper;
import com.jobpilot.modules.offer.prompt.OfferPromptFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Offer 决策（FR6）：录入 / 提取 / 分组对比 / 前景与倾向分析。
 *
 * <p>保留组最多 4 个（40914）；对比矩阵固定 12 个维度与顺序，缺失统一「未填写」；
 * 分析结果持久化最近一次，联网开关默认关闭。
 */
@Service
public class OfferService {

    private static final Logger log = LoggerFactory.getLogger(OfferService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final int MAX_RETRIES = 3;
    private static final int KEEP_LIMIT = 4;
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    private static final String MISSING = "未填写";
    private static final Set<String> IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/webp");
    private static final Set<String> TENDENCIES = Set.of("ACCEPT", "CONFIRM", "DECLINE");

    private final OfferMapper offerMapper;
    private final CompanyMapper companyMapper;
    private final JobPositionMapper jobMapper;
    private final JobApplicationMapper applicationMapper;
    private final LlmSceneService sceneService;
    private final ProviderClientRegistry registry;
    private final LlmCallLogger callLogger;
    private final OfferPromptFactory prompts;

    public OfferService(OfferMapper offerMapper, CompanyMapper companyMapper, JobPositionMapper jobMapper,
                        JobApplicationMapper applicationMapper, LlmSceneService sceneService,
                        ProviderClientRegistry registry, LlmCallLogger callLogger, OfferPromptFactory prompts) {
        this.offerMapper = offerMapper;
        this.companyMapper = companyMapper;
        this.jobMapper = jobMapper;
        this.applicationMapper = applicationMapper;
        this.sceneService = sceneService;
        this.registry = registry;
        this.callLogger = callLogger;
        this.prompts = prompts;
    }

    public PageResult<com.jobpilot.modules.offer.dto.Offer> page(Integer page, Integer size, String group) {
        int current = PageResult.normalizePage(page);
        int pageSize = PageResult.normalizeSize(size);
        Page<Offer> result = offerMapper.selectPage(new Page<>(current, pageSize),
                Wrappers.<Offer>lambdaQuery()
                        .eq(!blank(group), Offer::getOfferGroup, group)
                        .orderByDesc(Offer::getCreatedAt).orderByDesc(Offer::getId));
        Map<Long, String> companies = companyNames(result.getRecords());
        Map<Long, String> jobs = jobTitles(result.getRecords());
        return PageResult.of(result.getRecords().stream()
                        .map(offer -> toOffer(offer,
                                offer.getCompanyId() == null ? null : companies.get(offer.getCompanyId()),
                                offer.getJobPositionId() == null ? null : jobs.get(offer.getJobPositionId())))
                        .toList(),
                result.getTotal(), current, pageSize);
    }

    public OfferDetail detail(Long offerId) {
        return detailOf(require(offerId));
    }

    /** 新增：companyId / jobPositionId 至少一个；仅填岗位时自动带出公司（FR6.6）。 */
    public OfferDetail create(CreateOfferRequest request) {
        if (request.companyId() == null && request.jobPositionId() == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "companyId 与 jobPositionId 至少填写一个");
        }
        JobPosition job = request.jobPositionId() == null ? null : requireJob(request.jobPositionId());
        if (request.applicationId() != null) {
            requireApplication(request.applicationId());
        }
        Long companyId = request.companyId();
        if (companyId == null) {
            companyId = job == null ? null : job.getCompanyId();
        } else {
            requireCompany(companyId);
        }
        String group = blank(request.group()) ? "KEEP" : request.group();
        if ("KEEP".equals(group)) {
            requireKeepSlot(null);
        }
        boolean finalChoice = Boolean.TRUE.equals(request.finalChoice());
        if (finalChoice) {
            clearFinalChoice(null);
        }
        Offer entity = new Offer();
        entity.setUserId(1L);
        entity.setCompanyId(companyId);
        entity.setJobPositionId(request.jobPositionId());
        entity.setApplicationId(request.applicationId());
        entity.setMonthlySalary(request.monthlySalary());
        entity.setBaseSalary(request.baseSalary());
        entity.setPerformanceRatio(request.performanceRatio());
        entity.setSocialInsuranceBase(text(request.socialInsuranceBase(), 64));
        entity.setSocialInsuranceRatio(text(request.socialInsuranceRatio(), 32));
        entity.setHousingFundBase(text(request.housingFundBase(), 64));
        entity.setHousingFundRatio(text(request.housingFundRatio(), 32));
        entity.setTwoDayWeekend(request.twoDayWeekend());
        entity.setWorkHours(text(request.workHours(), 64));
        entity.setBenefits(write(strings(request.benefits())));
        entity.setAllowances(write(strings(request.allowances())));
        entity.setAnnualBonus(text(request.annualBonus(), 64));
        entity.setQuarterlyBonus(text(request.quarterlyBonus(), 64));
        entity.setRemark(text(request.remark(), 500));
        entity.setOfferGroup(group);
        entity.setDecisionPriorities(text(request.decisionPriorities(), 1000));
        entity.setFinalChoice(finalChoice);
        entity.setFinalChoiceReason(text(request.finalChoiceReason(), 1000));
        entity.setCreatedAt(OffsetDateTime.now());
        entity.setUpdatedAt(OffsetDateTime.now());
        offerMapper.insert(entity);
        return detailOf(require(entity.getId()));
    }

    /** 更新：未传字段不修改，显式 null 清空；移动回保留组同样受 4 个上限约束。 */
    public OfferDetail update(Long offerId, UpdateOfferRequest request) {
        Offer existing = require(offerId);
        LambdaUpdateWrapper<Offer> update = Wrappers.<Offer>lambdaUpdate().eq(Offer::getId, offerId);
        boolean changed = false;
        JobPosition job = null;
        boolean jobProvided = request.jobPositionId() != null;
        if (jobProvided && !request.jobPositionId().isNull()) {
            job = requireJob(integerNode(request.jobPositionId(), Long.MAX_VALUE));
        }
        if (jobProvided) {
            update.set(Offer::getJobPositionId, job == null ? null : job.getId());
            changed = true;
        }
        if (request.companyId() != null) {
            if (request.companyId().isNull()) {
                update.set(Offer::getCompanyId, null);
            } else {
                Long companyId = integerNode(request.companyId(), Long.MAX_VALUE);
                requireCompany(companyId);
                update.set(Offer::getCompanyId, companyId);
            }
            changed = true;
        } else if (job != null && job.getCompanyId() != null) {
            update.set(Offer::getCompanyId, job.getCompanyId());
            changed = true;
        }
        if (request.applicationId() != null) {
            if (request.applicationId().isNull()) {
                update.set(Offer::getApplicationId, null);
            } else {
                Long applicationId = integerNode(request.applicationId(), Long.MAX_VALUE);
                requireApplication(applicationId);
                update.set(Offer::getApplicationId, applicationId);
            }
            changed = true;
        }
        if (request.monthlySalary() != null) {
            update.set(Offer::getMonthlySalary, salary(request.monthlySalary()));
            changed = true;
        }
        if (request.baseSalary() != null) {
            update.set(Offer::getBaseSalary, salary(request.baseSalary()));
            changed = true;
        }
        if (request.performanceRatio() != null) {
            update.set(Offer::getPerformanceRatio, ratio(request.performanceRatio()));
            changed = true;
        }
        changed |= setText(update, Offer::getSocialInsuranceBase, request.socialInsuranceBase(), 64);
        changed |= setText(update, Offer::getSocialInsuranceRatio, request.socialInsuranceRatio(), 32);
        changed |= setText(update, Offer::getHousingFundBase, request.housingFundBase(), 64);
        changed |= setText(update, Offer::getHousingFundRatio, request.housingFundRatio(), 32);
        if (request.twoDayWeekend() != null) {
            update.set(Offer::getTwoDayWeekend, bool(request.twoDayWeekend()));
            changed = true;
        }
        changed |= setText(update, Offer::getWorkHours, request.workHours(), 64);
        if (request.benefits() != null) {
            update.setSql("benefits = {0}::jsonb", listJson(request.benefits()));
            changed = true;
        }
        if (request.allowances() != null) {
            update.setSql("allowances = {0}::jsonb", listJson(request.allowances()));
            changed = true;
        }
        changed |= setText(update, Offer::getAnnualBonus, request.annualBonus(), 64);
        changed |= setText(update, Offer::getQuarterlyBonus, request.quarterlyBonus(), 64);
        changed |= setText(update, Offer::getRemark, request.remark(), 500);
        changed |= setText(update, Offer::getDecisionPriorities, request.decisionPriorities(), 1000);
        if (request.finalChoice() != null) {
            Boolean value = bool(request.finalChoice());
            if (Boolean.TRUE.equals(value)) {
                clearFinalChoice(offerId);
            }
            update.set(Offer::getFinalChoice, Boolean.TRUE.equals(value));
            changed = true;
        }
        changed |= setText(update, Offer::getFinalChoiceReason, request.finalChoiceReason(), 1000);
        if (request.group() != null) {
            String group = group(request.group());
            if ("KEEP".equals(group) && !"KEEP".equals(existing.getOfferGroup())) {
                requireKeepSlot(offerId);
            }
            update.set(Offer::getOfferGroup, group);
            changed = true;
        }
        if (changed) {
            update.set(Offer::getUpdatedAt, OffsetDateTime.now());
            offerMapper.update(null, update);
        }
        return detailOf(require(offerId));
    }

    public void delete(Long offerId) {
        require(offerId);
        offerMapper.deleteById(offerId);
    }

    /** 固定 12 个维度、固定顺序的横向对比；保留组为空时列与行均为空数组。 */
    public OfferComparison comparison() {
        List<Offer> offers = offerMapper.selectList(Wrappers.<Offer>lambdaQuery()
                .eq(Offer::getOfferGroup, "KEEP")
                .orderByAsc(Offer::getCreatedAt).orderByAsc(Offer::getId));
        if (offers.isEmpty()) {
            return new OfferComparison(List.of(), List.of());
        }
        Map<Long, String> companies = companyNames(offers);
        Map<Long, String> jobs = jobTitles(offers);
        List<OfferComparisonColumn> columns = offers.stream()
                .map(offer -> new OfferComparisonColumn(offer.getId(),
                        offer.getCompanyId() == null ? null : companies.get(offer.getCompanyId()),
                        offer.getJobPositionId() == null ? null : jobs.get(offer.getJobPositionId())))
                .toList();
        List<OfferComparisonRow> rows = List.of(
                row("monthlySalary", "月薪", offers, offer -> salaryText(offer.getMonthlySalary())),
                row("baseSalary", "底薪", offers, offer -> salaryText(offer.getBaseSalary())),
                row("performanceRatio", "绩效占比", offers, offer -> percent(offer.getPerformanceRatio())),
                row("socialInsurance", "五险一金", offers, offer -> combine(
                        labeled("基数 ", offer.getSocialInsuranceBase(), 64),
                        labeled("比例 ", offer.getSocialInsuranceRatio(), 32))),
                row("housingFund", "公积金", offers, offer -> combine(
                        labeled("基数 ", offer.getHousingFundBase(), 64),
                        labeled("比例 ", offer.getHousingFundRatio(), 32))),
                row("twoDayWeekend", "双休", offers, offer -> Boolean.TRUE.equals(offer.getTwoDayWeekend())
                        ? "双休" : Boolean.FALSE.equals(offer.getTwoDayWeekend()) ? "非双休" : MISSING),
                row("workHours", "上下班时间", offers, offer -> value(offer.getWorkHours())),
                row("benefits", "福利", offers, offer -> list(offer.getBenefits())),
                row("allowances", "各项补贴", offers, offer -> list(offer.getAllowances())),
                row("annualBonus", "年终奖", offers, offer -> value(offer.getAnnualBonus())),
                row("quarterlyBonus", "季度奖", offers, offer -> value(offer.getQuarterlyBonus())),
                row("remark", "备注", offers, offer -> value(offer.getRemark())));
        return new OfferComparison(columns, rows);
    }

    /** 从文本 / 图片提取草稿（不落库）；图片走视觉消息，失败引导改用文本。 */
    public OfferExtraction extract(MultipartFile image, String text) {
        boolean hasImage = image != null && !image.isEmpty();
        boolean hasText = !blank(text);
        if (!hasImage && !hasText) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "text 与 image 至少提供一个");
        }
        LlmSceneService.SceneModel target = sceneService.resolveGlobal();
        LlmClient client = registry.get(target.providerKey());
        List<ChatMessage> messages;
        if (hasImage) {
            String dataUrl = dataUrl(image);
            messages = List.of(ChatMessage.system(prompts.extractSystem()),
                    ChatMessage.userWithImage(prompts.extractUser("见图片"), dataUrl));
        } else {
            messages = List.of(ChatMessage.system(prompts.extractSystem()),
                    ChatMessage.user(prompts.extractUser(text)));
        }
        try {
            AiOfferExtraction ai = structured(new StructuredOutputInvoker(client), target, messages,
                    AiOfferExtraction.class, "OFFER_EXTRACT", extracted -> { });
            return sanitize(ai);
        } catch (BizException ex) {
            if (hasImage) {
                throw BizException.of(ErrorCode.BAD_REQUEST,
                        "图片提取失败，当前模型可能不支持图片输入，请改用文本粘贴");
            }
            throw ex;
        }
    }

    /** 前景与倾向分析：基于用户资料，联网默认关闭；结果覆盖持久化（FR6.4 / FR6.5）。 */
    public OfferAnalysis analyze(Long offerId, AnalyzeOfferRequest request) {
        Offer offer = require(offerId);
        boolean webSearch = request != null && Boolean.TRUE.equals(request.webSearch());
        Company company = offer.getCompanyId() == null ? null : companyMapper.selectById(offer.getCompanyId());
        JobPosition job = offer.getJobPositionId() == null ? null : jobMapper.selectById(offer.getJobPositionId());
        LlmSceneService.SceneModel target = sceneService.resolve(LlmScene.OFFER_ANALYZE);
        LlmClient client = registry.get(target.providerKey());
        List<ChatMessage> messages = List.of(
                ChatMessage.system(prompts.analysisSystem(webSearch)),
                ChatMessage.user(prompts.analysisUser(materials(offer, company, job))));
        AiOfferAnalysis ai = structured(new StructuredOutputInvoker(client), target, messages,
                AiOfferAnalysis.class, "OFFER_ANALYZE", this::validate);
        OffsetDateTime now = OffsetDateTime.now();
        OfferAnalysis analysis = new OfferAnalysis(ai.tendency().trim().toUpperCase(Locale.ROOT),
                strings(ai.reasons()), text(ai.prospect(), 2000), strings(ai.risks()),
                webSearch ? strings(ai.sources()) : List.of(), !webSearch,
                target.providerKey(), target.model(), now);
        offerMapper.update(null, Wrappers.<Offer>lambdaUpdate()
                .eq(Offer::getId, offerId)
                .setSql("analysis = {0}::jsonb", write(analysis))
                .set(Offer::getAnalyzedAt, now)
                .set(Offer::getUpdatedAt, now));
        return analysis;
    }

    // ---------- LLM 调用与校验 ----------

    private <T> T structured(StructuredOutputInvoker invoker, LlmSceneService.SceneModel target,
                             List<ChatMessage> messages, Class<T> type, String scene, Consumer<T> validator) {
        String lastError = "未知错误";
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            long start = System.nanoTime();
            try {
                StructuredOutputInvoker.Parsed<T> parsed = invoker.invokeWithUsage(target.model(), messages, type);
                validator.accept(parsed.value());
                ChatResult usage = parsed.result();
                callLogger.log(scene, target.providerKey(), target.model(),
                        usage.promptTokens(), usage.completionTokens(), usage.totalTokens(),
                        elapsed(start), true, null);
                return parsed.value();
            } catch (Exception ex) {
                lastError = ex.getMessage();
                callLogger.log(scene, target.providerKey(), target.model(),
                        null, null, null, elapsed(start), false, lastError);
                log.warn("{} 失败第 {} 次: {}", scene, attempt + 1, lastError);
            }
        }
        throw BizException.of(ErrorCode.INTERNAL_ERROR, "模型输出连续失败：" + lastError);
    }

    private void validate(AiOfferAnalysis ai) {
        String tendency = ai == null || ai.tendency() == null ? "" : ai.tendency().trim().toUpperCase(Locale.ROOT);
        if (!TENDENCIES.contains(tendency)) {
            throw new IllegalArgumentException("tendency 取值非法: " + tendency);
        }
        if (ai.reasons() == null || ai.reasons().isEmpty()) {
            throw new IllegalArgumentException("缺少倾向理由");
        }
        if (ai.prospect() == null || ai.prospect().isBlank()) {
            throw new IllegalArgumentException("缺少前景分析");
        }
    }

    // ---------- 校验辅助 ----------

    private void requireKeepSlot(Long selfId) {
        Long keep = offerMapper.selectCount(Wrappers.<Offer>lambdaQuery()
                .eq(Offer::getOfferGroup, "KEEP")
                .ne(selfId != null, Offer::getId, selfId));
        if (keep != null && keep >= KEEP_LIMIT) {
            throw BizException.of(ErrorCode.OFFER_KEEP_LIMIT);
        }
    }

    /** 最终选择全局唯一：标记前先清除其他 Offer（FR6.7）。 */
    private void clearFinalChoice(Long excludeId) {
        offerMapper.update(null, Wrappers.<Offer>lambdaUpdate()
                .eq(Offer::getFinalChoice, true)
                .ne(excludeId != null, Offer::getId, excludeId)
                .set(Offer::getFinalChoice, false)
                .set(Offer::getUpdatedAt, OffsetDateTime.now()));
    }

    private Offer require(Long offerId) {
        Offer entity = offerMapper.selectById(offerId);
        if (entity == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "Offer 不存在: " + offerId);
        }
        return entity;
    }

    private JobPosition requireJob(Long jobPositionId) {
        JobPosition job = jobMapper.selectById(jobPositionId);
        if (job == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "岗位不存在: " + jobPositionId);
        }
        return job;
    }

    private void requireCompany(Long companyId) {
        if (companyMapper.selectById(companyId) == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "公司不存在: " + companyId);
        }
    }

    private static Long integerNode(JsonNode node, long limit) {
        if (node == null || node.isNull()) {
            return null;
        }
        long value = node.asLong();
        if (value < 0 || value > limit) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "数值超出允许范围");
        }
        return value;
    }

    private static Integer salary(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        int value = node.asInt();
        if (value < 0 || value > 10_000_000) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "薪资超出允许范围");
        }
        return value;
    }

    private static BigDecimal ratio(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        BigDecimal value = node.decimalValue();
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "比例必须在 0-100 之间");
        }
        return value;
    }

    private static Boolean bool(JsonNode node) {
        return node == null || node.isNull() ? null : node.asBoolean();
    }

    private static String group(JsonNode node) {
        String value = node == null || node.isNull() ? null
                : node.asText().trim().toUpperCase(Locale.ROOT);
        if (!"KEEP".equals(value) && !"DROP".equals(value)) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "group 取值非法");
        }
        return value;
    }

    private static boolean setText(LambdaUpdateWrapper<Offer> update, com.baomidou.mybatisplus.core.toolkit.support.SFunction<Offer, ?> column,
                                   JsonNode node, int max) {
        if (node == null) {
            return false;
        }
        update.set(column, node.isNull() ? null : text(node.asText(), max));
        return true;
    }

    private static String listJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return "[]";
        }
        if (!node.isArray()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "列表字段必须是数组");
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            if (item != null && !item.isNull() && !item.asText().isBlank()) {
                values.add(item.asText().trim());
            }
        });
        return write(strings(values));
    }

    // ---------- 响应组装 ----------

    private OfferDetail detailOf(Offer offer) {
        Company company = offer.getCompanyId() == null ? null : companyMapper.selectById(offer.getCompanyId());
        JobPosition job = offer.getJobPositionId() == null ? null : jobMapper.selectById(offer.getJobPositionId());
        return new OfferDetail(offer.getId(), offer.getCompanyId(), company == null ? null : company.getName(),
                offer.getJobPositionId(), offer.getApplicationId(), job == null ? null : job.getTitle(),
                offer.getMonthlySalary(), offer.getBaseSalary(), offer.getPerformanceRatio(),
                offer.getSocialInsuranceBase(), offer.getSocialInsuranceRatio(),
                offer.getHousingFundBase(), offer.getHousingFundRatio(), offer.getTwoDayWeekend(),
                offer.getWorkHours(), strings(offer.getBenefits()), strings(offer.getAllowances()),
                offer.getAnnualBonus(), offer.getQuarterlyBonus(), offer.getRemark(), offer.getOfferGroup(),
                offer.getDecisionPriorities(), Boolean.TRUE.equals(offer.getFinalChoice()),
                offer.getFinalChoiceReason(),
                offer.getCreatedAt(), offer.getUpdatedAt(), analysis(offer.getAnalysis()));
    }

    private com.jobpilot.modules.offer.dto.Offer toOffer(Offer offer, String companyName, String jobTitle) {
        return new com.jobpilot.modules.offer.dto.Offer(offer.getId(), offer.getCompanyId(), companyName,
                offer.getJobPositionId(), offer.getApplicationId(), jobTitle, offer.getMonthlySalary(),
                offer.getBaseSalary(), offer.getPerformanceRatio(), offer.getSocialInsuranceBase(),
                offer.getSocialInsuranceRatio(), offer.getHousingFundBase(), offer.getHousingFundRatio(),
                offer.getTwoDayWeekend(), offer.getWorkHours(), strings(offer.getBenefits()),
                strings(offer.getAllowances()), offer.getAnnualBonus(), offer.getQuarterlyBonus(),
                offer.getRemark(), offer.getOfferGroup(), offer.getDecisionPriorities(),
                Boolean.TRUE.equals(offer.getFinalChoice()), offer.getFinalChoiceReason(),
                offer.getCreatedAt(), offer.getUpdatedAt());
    }

    /** 投递时间线用：按 applicationId 取关联 Offer（最多一条，重复关联时取最新）。 */
    public com.jobpilot.modules.offer.dto.Offer findByApplicationId(Long applicationId) {
        Offer offer = offerMapper.selectOne(Wrappers.<Offer>lambdaQuery()
                .eq(Offer::getApplicationId, applicationId)
                .orderByDesc(Offer::getId).last("limit 1"));
        if (offer == null) {
            return null;
        }
        Company company = offer.getCompanyId() == null ? null : companyMapper.selectById(offer.getCompanyId());
        JobPosition job = offer.getJobPositionId() == null ? null : jobMapper.selectById(offer.getJobPositionId());
        return toOffer(offer, company == null ? null : company.getName(), job == null ? null : job.getTitle());
    }

    private void requireApplication(Long applicationId) {
        JobApplication application = applicationMapper.selectById(applicationId);
        if (application == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "投递记录不存在: " + applicationId);
        }
    }

    private OfferAnalysis analysis(String json) {
        if (blank(json)) {
            return null;
        }
        try {
            return MAPPER.readValue(json, new TypeReference<OfferAnalysis>() { });
        } catch (Exception ex) {
            log.warn("Offer 分析结果解析失败", ex);
            return null;
        }
    }

    private Map<Long, String> companyNames(List<Offer> offers) {
        List<Long> ids = offers.stream().map(Offer::getCompanyId).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return companyMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(Company::getId, Company::getName));
    }

    private Map<Long, String> jobTitles(List<Offer> offers) {
        List<Long> ids = offers.stream().map(Offer::getJobPositionId).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return jobMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(JobPosition::getId, JobPosition::getTitle));
    }

    // ---------- 提取与分析辅助 ----------

    /** 提取结果兜底：数值越界置 null，字符串截断，列表去重。 */
    private static OfferExtraction sanitize(AiOfferExtraction ai) {
        AiOfferExtraction value = ai == null ? new AiOfferExtraction(null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null) : ai;
        return new OfferExtraction(
                text(value.companyName(), 128), text(value.jobPositionTitle(), 128),
                money(value.monthlySalary()), money(value.baseSalary()), ratio(value.performanceRatio()),
                text(value.socialInsuranceBase(), 64), text(value.socialInsuranceRatio(), 32),
                text(value.housingFundBase(), 64), text(value.housingFundRatio(), 32),
                value.twoDayWeekend(), text(value.workHours(), 64),
                strings(value.benefits()), strings(value.allowances()),
                text(value.annualBonus(), 64), text(value.quarterlyBonus(), 64), text(value.remark(), 500));
    }

    private static Integer money(Integer value) {
        return value == null || value < 0 ? null : Math.min(value, 10_000_000);
    }

    private static BigDecimal ratio(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) {
            return null;
        }
        return value.compareTo(BigDecimal.valueOf(100)) > 0 ? BigDecimal.valueOf(100) : value;
    }

    private static String materials(Offer offer, Company company, JobPosition job) {
        StringBuilder builder = new StringBuilder();
        if (company != null) {
            builder.append("公司：").append(company.getName()).append('\n');
            append(builder, "公司简介", company.getIntro());
            append(builder, "公司标签", company.getTags());
        }
        if (job != null) {
            append(builder, "岗位名称", job.getTitle());
            append(builder, "城市", job.getCity());
            append(builder, "经验要求", job.getExperience());
            append(builder, "学历要求", job.getEducation());
            if (job.getSalaryMin() != null || job.getSalaryMax() != null) {
                builder.append("岗位薪资：").append(job.getSalaryMin() == null ? "?" : job.getSalaryMin())
                        .append('-').append(job.getSalaryMax() == null ? "?" : job.getSalaryMax())
                        .append(" 元/月\n");
            }
            append(builder, "必备技能", job.getMustHaveSkills());
            append(builder, "职责摘要", job.getResponsibility());
        }
        builder.append("\n用户填写的 Offer 条款：\n");
        append(builder, "月薪", offer.getMonthlySalary() == null ? null : offer.getMonthlySalary() + " 元/月");
        append(builder, "底薪", offer.getBaseSalary() == null ? null : offer.getBaseSalary() + " 元/月");
        append(builder, "绩效占比", offer.getPerformanceRatio() == null
                ? null : offer.getPerformanceRatio().stripTrailingZeros().toPlainString() + "%");
        append(builder, "五险一金基数", offer.getSocialInsuranceBase());
        append(builder, "五险一金比例", offer.getSocialInsuranceRatio());
        append(builder, "公积金基数", offer.getHousingFundBase());
        append(builder, "公积金比例", offer.getHousingFundRatio());
        append(builder, "双休", Boolean.TRUE.equals(offer.getTwoDayWeekend()) ? "是"
                : Boolean.FALSE.equals(offer.getTwoDayWeekend()) ? "否" : null);
        append(builder, "上下班时间", offer.getWorkHours());
        append(builder, "福利", list(offer.getBenefits()));
        append(builder, "各项补贴", list(offer.getAllowances()));
        append(builder, "年终奖", offer.getAnnualBonus());
        append(builder, "季度奖", offer.getQuarterlyBonus());
        append(builder, "备注", offer.getRemark());
        return builder.toString();
    }

    private static void append(StringBuilder builder, String label, String value) {
        if (!blank(value) && !MISSING.equals(value)) {
            builder.append(label).append('：').append(value).append('\n');
        }
    }

    private static String dataUrl(MultipartFile image) {
        if (image.getSize() > MAX_IMAGE_BYTES) {
            throw BizException.of(ErrorCode.PAYLOAD_TOO_LARGE, "图片不能超过 5MB");
        }
        String contentType = image.getContentType() == null ? ""
                : image.getContentType().toLowerCase(Locale.ROOT);
        String normalized = "image/jpg".equals(contentType) ? "image/jpeg" : contentType;
        if (!IMAGE_TYPES.contains(normalized)) {
            throw BizException.of(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "仅支持 PNG / JPEG / WEBP 图片");
        }
        try {
            return "data:" + normalized + ";base64," + Base64.getEncoder().encodeToString(image.getBytes());
        } catch (Exception ex) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "图片读取失败，请重新上传");
        }
    }

    // ---------- 对比行辅助 ----------

    private static OfferComparisonRow row(String key, String label, List<Offer> offers,
                                          java.util.function.Function<Offer, String> extractor) {
        return new OfferComparisonRow(key, label, offers.stream().map(extractor).toList());
    }

    private static String salaryText(Integer value) {
        return value == null ? MISSING : value + " 元/月";
    }

    private static String percent(BigDecimal value) {
        return value == null ? MISSING : value.stripTrailingZeros().toPlainString() + "%";
    }

    private static String labeled(String label, String value, int max) {
        String text = text(value, max);
        return text == null ? label + MISSING : label + text;
    }

    private static String combine(String first, String second) {
        String left = first.endsWith(MISSING) ? null : first;
        String right = second.endsWith(MISSING) ? null : second;
        if (left == null && right == null) {
            return MISSING;
        }
        return (left == null ? "" : left) + (right == null ? "" : (left == null ? "" : " / ") + right);
    }

    private static String value(String text) {
        return blank(text) ? MISSING : text;
    }

    private static String list(String json) {
        List<String> values = strings(json);
        return values.isEmpty() ? MISSING : String.join("、", values);
    }

    // ---------- 通用辅助 ----------

    private static String text(String value, int max) {
        if (blank(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    /** 文字清单兜底：去空、去重（保序）、单条截断，最多 50 条。 */
    private static List<String> strings(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(value -> !blank(value))
                .map(value -> text(value, 64)).distinct().limit(50).toList();
    }

    private static List<String> strings(String json) {
        if (blank(json)) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
