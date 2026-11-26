package com.jobpilot.modules.company.service;

import java.time.OffsetDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.api.PageResult;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.modules.company.dto.CompanyResponse;
import com.jobpilot.modules.company.dto.CreateCompanyRequest;
import com.jobpilot.modules.company.dto.MergeCompaniesRequest;
import com.jobpilot.modules.company.dto.UpdateCompanyRequest;
import com.jobpilot.modules.company.entity.Company;
import com.jobpilot.modules.company.mapper.CompanyMapper;
import com.jobpilot.modules.job.entity.JobPosition;
import com.jobpilot.modules.job.mapper.JobPositionMapper;
import com.jobpilot.modules.offer.entity.Offer;
import com.jobpilot.modules.offer.mapper.OfferMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 公司最小 CRUD（F5 / F6 数据前置）：名称全等查重，被岗位引用时禁止删除。
 */
@Service
public class CompanyService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CompanyMapper companyMapper;
    private final JobPositionMapper jobPositionMapper;
    private final OfferMapper offerMapper;

    public CompanyService(CompanyMapper companyMapper, JobPositionMapper jobPositionMapper,
                          OfferMapper offerMapper) {
        this.companyMapper = companyMapper;
        this.jobPositionMapper = jobPositionMapper;
        this.offerMapper = offerMapper;
    }

    public PageResult<CompanyResponse> page(Integer page, Integer size, String keyword) {
        int current = PageResult.normalizePage(page);
        int pageSize = PageResult.normalizeSize(size);
        String kw = keyword == null ? null : keyword.trim();
        Page<Company> result = companyMapper.selectPage(new Page<>(current, pageSize),
                Wrappers.<Company>lambdaQuery()
                        .like(kw != null && !kw.isEmpty(), Company::getName, kw)
                        .orderByDesc(Company::getCreatedAt).orderByDesc(Company::getId));
        List<CompanyResponse> items = result.getRecords().stream()
                .map(company -> toResponse(company, jobPositionMapper.selectCount(
                        Wrappers.<JobPosition>lambdaQuery().eq(JobPosition::getCompanyId, company.getId()))))
                .toList();
        return PageResult.of(items, result.getTotal(), current, pageSize);
    }

    @Transactional
    public CompanyResponse create(CreateCompanyRequest request) {
        String name = request.name().trim();
        if (name.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "公司名称不能为空");
        }
        ensureNameAvailable(name, null);
        Company entity = new Company();
        entity.setUserId(1L);
        entity.setName(name);
        entity.setIntro(request.intro());
        entity.setTags(tagsJson(request.tags()));
        try {
            companyMapper.insert(entity);
        } catch (DuplicateKeyException ex) {
            throw BizException.of(ErrorCode.COMPANY_EXISTS);
        }
        return toResponse(companyMapper.selectById(entity.getId()), 0L);
    }

    @Transactional
    public CompanyResponse update(Long companyId, UpdateCompanyRequest request) {
        Company entity = require(companyId);
        if (request.name() != null) {
            String name = request.name().trim();
            if (name.isEmpty()) {
                throw BizException.of(ErrorCode.BAD_REQUEST, "公司名称不能为空");
            }
            if (!name.equals(entity.getName())) {
                ensureNameAvailable(name, companyId);
                entity.setName(name);
            }
        }
        if (request.intro() != null) {
            entity.setIntro(request.intro());
        }
        if (request.tags() != null) {
            entity.setTags(tagsJson(request.tags()));
        }
        entity.setUpdatedAt(OffsetDateTime.now());
        try {
            companyMapper.updateById(entity);
        } catch (DuplicateKeyException ex) {
            throw BizException.of(ErrorCode.COMPANY_EXISTS);
        }
        return toResponse(entity, jobPositionMapper.selectCount(
                Wrappers.<JobPosition>lambdaQuery().eq(JobPosition::getCompanyId, companyId)));
    }

    @Transactional
    public void delete(Long companyId) {
        require(companyId);
        Long referenced = jobPositionMapper.selectCount(Wrappers.<JobPosition>lambdaQuery()
                .eq(JobPosition::getCompanyId, companyId));
        if (referenced > 0) {
            throw BizException.of(ErrorCode.RESOURCE_REFERENCED,
                    "公司仍被 " + referenced + " 个岗位引用，请先解除岗位关联");
        }
        companyMapper.deleteById(companyId);
    }

    /** 手动合并（FR5.3 事后补救）：岗位与 Offer 归并到目标公司，再删除源公司。 */
    @Transactional
    public CompanyResponse merge(MergeCompaniesRequest request) {
        Long sourceId = request.sourceCompanyId();
        Long targetId = request.targetCompanyId();
        if (sourceId.equals(targetId)) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "不能合并到自身");
        }
        require(sourceId);
        require(targetId);
        OffsetDateTime now = OffsetDateTime.now();
        jobPositionMapper.update(null, Wrappers.<JobPosition>lambdaUpdate()
                .eq(JobPosition::getCompanyId, sourceId)
                .set(JobPosition::getCompanyId, targetId)
                .set(JobPosition::getUpdatedAt, now));
        offerMapper.update(null, Wrappers.<Offer>lambdaUpdate()
                .eq(Offer::getCompanyId, sourceId)
                .set(Offer::getCompanyId, targetId));
        companyMapper.deleteById(sourceId);
        return toResponse(companyMapper.selectById(targetId), jobPositionMapper.selectCount(
                Wrappers.<JobPosition>lambdaQuery().eq(JobPosition::getCompanyId, targetId)));
    }

    public Company require(Long companyId) {
        Company company = companyMapper.selectById(companyId);
        if (company == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "公司不存在: " + companyId);
        }
        return company;
    }

    private void ensureNameAvailable(String name, Long excludeId) {
        Long exists = companyMapper.selectCount(Wrappers.<Company>lambdaQuery()
                .eq(Company::getName, name)
                .ne(excludeId != null, Company::getId, excludeId));
        if (exists > 0) {
            throw BizException.of(ErrorCode.COMPANY_EXISTS);
        }
    }

    private CompanyResponse toResponse(Company company, Long jobPositionCount) {
        return new CompanyResponse(company.getId(), company.getName(), company.getIntro(),
                tags(company.getTags()), jobPositionCount.intValue(),
                company.getCreatedAt(), company.getUpdatedAt());
    }

    private static String tagsJson(List<String> tags) {
        try {
            return MAPPER.writeValueAsString(tags == null ? List.of() : tags);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private static List<String> tags(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception ex) {
            return List.of();
        }
    }
}
