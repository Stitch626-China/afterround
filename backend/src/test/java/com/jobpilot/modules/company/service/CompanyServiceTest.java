package com.jobpilot.modules.company.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jobpilot.LambdaCacheSupport;
import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.modules.company.dto.CreateCompanyRequest;
import com.jobpilot.modules.company.dto.MergeCompaniesRequest;
import com.jobpilot.modules.company.dto.UpdateCompanyRequest;
import com.jobpilot.modules.company.entity.Company;
import com.jobpilot.modules.company.mapper.CompanyMapper;
import com.jobpilot.modules.job.entity.JobPosition;
import com.jobpilot.modules.job.mapper.JobPositionMapper;
import com.jobpilot.modules.offer.entity.Offer;
import com.jobpilot.modules.offer.mapper.OfferMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 公司 CRUD：名称全等查重、标签序列化、被岗位引用时禁止删除。 */
@ExtendWith(MockitoExtension.class)
class CompanyServiceTest {

    @Mock
    private CompanyMapper companyMapper;
    @Mock
    private JobPositionMapper jobPositionMapper;
    @Mock
    private OfferMapper offerMapper;

    @BeforeAll
    static void initLambdaCache() {
        LambdaCacheSupport.init(Company.class, JobPosition.class, Offer.class);
    }

    private CompanyService service() {
        return new CompanyService(companyMapper, jobPositionMapper, offerMapper);
    }

    private static Company company() {
        Company company = new Company();
        company.setId(1L);
        company.setName("字节跳动");
        company.setTags("[\"互联网\"]");
        company.setCreatedAt(OffsetDateTime.now());
        company.setUpdatedAt(OffsetDateTime.now());
        return company;
    }

    @Test
    @SuppressWarnings("unchecked")
    void pageMapsTagsAndCountsPositions() {
        Page<Company> page = new Page<>(1, 20);
        page.setRecords(List.of(company()));
        page.setTotal(1);
        when(companyMapper.selectPage(any(), any())).thenReturn(page);
        when(jobPositionMapper.selectCount(any())).thenReturn(3L);

        var result = service().page(1, 20, "字节");

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.name()).isEqualTo("字节跳动");
            assertThat(item.tags()).containsExactly("互联网");
            assertThat(item.jobPositionCount()).isEqualTo(3);
        });
    }

    @Test
    void createRejectsBlankAndDuplicateName() {
        assertCode(() -> service().create(new CreateCompanyRequest("  ", null, null)), ErrorCode.BAD_REQUEST);

        when(companyMapper.selectCount(any())).thenReturn(1L);
        assertCode(() -> service().create(new CreateCompanyRequest("字节跳动", null, null)),
                ErrorCode.COMPANY_EXISTS);
    }

    @Test
    void createPersistsTrimmedName() {
        when(companyMapper.selectCount(any())).thenReturn(0L);
        when(companyMapper.selectById(any())).thenReturn(company());

        var response = service().create(new CreateCompanyRequest(" 字节跳动 ", null, List.of("互联网")));

        assertThat(response.name()).isEqualTo("字节跳动");
        verify(companyMapper).insert(any(Company.class));
    }

    @Test
    void updateChecksDuplicateOnlyWhenNameChanges() {
        when(companyMapper.selectById(1L)).thenReturn(company());
        when(companyMapper.selectCount(any())).thenReturn(1L);
        assertCode(() -> service().update(1L, new UpdateCompanyRequest("快手", null, null)),
                ErrorCode.COMPANY_EXISTS);

        when(jobPositionMapper.selectCount(any())).thenReturn(1L);
        var response = service().update(1L, new UpdateCompanyRequest(null, "介绍", List.of("短视频")));
        assertThat(response.intro()).isEqualTo("介绍");
        assertThat(response.tags()).containsExactly("短视频");
        verify(companyMapper).updateById(any(Company.class));
    }

    @Test
    void updateRejectsMissingCompany() {
        when(companyMapper.selectById(9L)).thenReturn(null);
        assertCode(() -> service().update(9L, new UpdateCompanyRequest("x", null, null)), ErrorCode.NOT_FOUND);
    }

    @Test
    void deleteRejectsReferencedCompany() {
        when(companyMapper.selectById(1L)).thenReturn(company());
        when(jobPositionMapper.selectCount(any())).thenReturn(2L);

        assertCode(() -> service().delete(1L), ErrorCode.RESOURCE_REFERENCED);
        verify(companyMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void deleteRemovesUnreferencedCompany() {
        when(companyMapper.selectById(1L)).thenReturn(company());
        when(jobPositionMapper.selectCount(any())).thenReturn(0L);

        service().delete(1L);

        verify(companyMapper).deleteById(1L);
    }

    @Test
    void mergeMovesJobsAndOffersThenDeletesSource() {
        Company target = company();
        target.setId(2L);
        target.setName("字节");
        when(companyMapper.selectById(1L)).thenReturn(company());
        when(companyMapper.selectById(2L)).thenReturn(target);
        when(jobPositionMapper.selectCount(any())).thenReturn(4L);

        var response = service().merge(new MergeCompaniesRequest(1L, 2L));

        assertThat(response.id()).isEqualTo(2L);
        assertThat(response.jobPositionCount()).isEqualTo(4);
        verify(jobPositionMapper).update(any(), any());
        verify(offerMapper).update(any(), any());
        verify(companyMapper).deleteById(1L);
    }

    @Test
    void mergeRejectsSameCompanyAndMissingTarget() {
        assertCode(() -> service().merge(new MergeCompaniesRequest(1L, 1L)), ErrorCode.BAD_REQUEST);

        when(companyMapper.selectById(1L)).thenReturn(company());
        when(companyMapper.selectById(2L)).thenReturn(null);
        assertCode(() -> service().merge(new MergeCompaniesRequest(1L, 2L)), ErrorCode.NOT_FOUND);
    }

    private static void assertCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class,
                ex -> assertThat(ex.errorCode()).isEqualTo(expected));
    }
}
