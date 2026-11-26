package com.jobpilot.modules.company.controller;

import com.jobpilot.common.api.PageResult;
import com.jobpilot.common.api.Result;
import com.jobpilot.modules.company.dto.CompanyResponse;
import com.jobpilot.modules.company.dto.CreateCompanyRequest;
import com.jobpilot.modules.company.dto.MergeCompaniesRequest;
import com.jobpilot.modules.company.dto.UpdateCompanyRequest;
import com.jobpilot.modules.company.service.CompanyService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 公司最小 CRUD API（contracts/openapi.yaml：公司）。 */
@RestController
@RequestMapping("/api/companies")
public class CompanyController {

    private final CompanyService companyService;

    public CompanyController(CompanyService companyService) {
        this.companyService = companyService;
    }

    @GetMapping
    public Result<PageResult<CompanyResponse>> list(@RequestParam(required = false) Integer page,
                                                    @RequestParam(required = false) Integer size,
                                                    @RequestParam(required = false) String keyword) {
        return Result.ok(companyService.page(page, size, keyword));
    }

    @PostMapping
    public Result<CompanyResponse> create(@Valid @RequestBody CreateCompanyRequest request) {
        return Result.ok(companyService.create(request));
    }

    @PostMapping("/merge")
    public Result<CompanyResponse> merge(@Valid @RequestBody MergeCompaniesRequest request) {
        return Result.ok(companyService.merge(request));
    }

    @PutMapping("/{companyId}")
    public Result<CompanyResponse> update(@PathVariable Long companyId,
                                          @Valid @RequestBody UpdateCompanyRequest request) {
        return Result.ok(companyService.update(companyId, request));
    }

    @DeleteMapping("/{companyId}")
    public Result<Void> delete(@PathVariable Long companyId) {
        companyService.delete(companyId);
        return Result.ok();
    }
}
