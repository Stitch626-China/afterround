package com.jobpilot.modules.review.controller;

import java.util.List;

import com.jobpilot.common.api.Result;
import com.jobpilot.modules.review.dto.ReviewTaxonomyResponse;
import com.jobpilot.modules.review.dto.TaxonomyDimensionResponse;
import com.jobpilot.modules.review.dto.TaxonomyItemResponse;
import com.jobpilot.modules.review.taxonomy.ReviewTaxonomyCatalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 复盘受控词表 API（contracts/openapi.yaml：F5 词表 1 个接口）。 */
@RestController
@RequestMapping("/api/review-taxonomy")
public class ReviewTaxonomyController {

    @GetMapping
    public Result<ReviewTaxonomyResponse> taxonomy() {
        List<TaxonomyItemResponse> topics = ReviewTaxonomyCatalog.TOPICS.stream()
                .map(item -> new TaxonomyItemResponse(item.key(), item.label(), item.description())).toList();
        List<TaxonomyItemResponse> lossTags = ReviewTaxonomyCatalog.LOSS_TAGS.stream()
                .map(item -> new TaxonomyItemResponse(item.key(), item.label(), item.description())).toList();
        List<TaxonomyDimensionResponse> dimensions = ReviewTaxonomyCatalog.DIMENSIONS.stream()
                .map(item -> new TaxonomyDimensionResponse(item.key(), item.label(), item.description(),
                        item.maxScore())).toList();
        return Result.ok(new ReviewTaxonomyResponse(topics, lossTags, dimensions,
                ReviewTaxonomyCatalog.TAXONOMY_VERSION, ReviewTaxonomyCatalog.RUBRIC_VERSION,
                ReviewTaxonomyCatalog.UPDATED_AT));
    }
}
