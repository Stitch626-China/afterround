package com.jobpilot.modules.offer.controller;

import com.jobpilot.common.api.PageResult;
import com.jobpilot.common.api.Result;
import com.jobpilot.modules.offer.dto.AnalyzeOfferRequest;
import com.jobpilot.modules.offer.dto.CreateOfferRequest;
import com.jobpilot.modules.offer.dto.OfferAnalysis;
import com.jobpilot.modules.offer.dto.OfferComparison;
import com.jobpilot.modules.offer.dto.OfferDetail;
import com.jobpilot.modules.offer.dto.OfferExtraction;
import com.jobpilot.modules.offer.dto.UpdateOfferRequest;
import com.jobpilot.modules.offer.service.OfferService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Offer 决策 API（contracts/openapi.yaml：F6 共 8 个端点）。 */
@RestController
@RequestMapping("/api/offers")
public class OfferController {

    private final OfferService offerService;

    public OfferController(OfferService offerService) {
        this.offerService = offerService;
    }

    @GetMapping
    public Result<PageResult<com.jobpilot.modules.offer.dto.Offer>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String group) {
        return Result.ok(offerService.page(page, size, group));
    }

    @PostMapping
    public Result<OfferDetail> create(@Valid @RequestBody CreateOfferRequest request) {
        return Result.ok(offerService.create(request));
    }

    @PostMapping("/extract")
    public Result<OfferExtraction> extract(
            @RequestPart(value = "image", required = false) MultipartFile image,
            @RequestParam(value = "text", required = false) String text) {
        return Result.ok(offerService.extract(image, text));
    }

    @GetMapping("/comparison")
    public Result<OfferComparison> comparison() {
        return Result.ok(offerService.comparison());
    }

    @GetMapping("/{offerId}")
    public Result<OfferDetail> detail(@PathVariable Long offerId) {
        return Result.ok(offerService.detail(offerId));
    }

    @PutMapping("/{offerId}")
    public Result<OfferDetail> update(@PathVariable Long offerId,
                                      @Valid @RequestBody UpdateOfferRequest request) {
        return Result.ok(offerService.update(offerId, request));
    }

    @DeleteMapping("/{offerId}")
    public Result<Void> delete(@PathVariable Long offerId) {
        offerService.delete(offerId);
        return Result.ok();
    }

    @PostMapping("/{offerId}/analyze")
    public Result<OfferAnalysis> analyze(@PathVariable Long offerId,
                                         @RequestBody(required = false) AnalyzeOfferRequest request) {
        return Result.ok(offerService.analyze(offerId, request));
    }
}
