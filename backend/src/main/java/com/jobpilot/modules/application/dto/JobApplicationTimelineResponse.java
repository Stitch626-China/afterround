package com.jobpilot.modules.application.dto;

import java.util.List;

import com.jobpilot.modules.offer.dto.Offer;
import com.jobpilot.modules.review.dto.ImprovementActionResponse;
import com.jobpilot.modules.review.dto.InterviewEventListItemResponse;
import com.jobpilot.modules.review.dto.ReviewSessionListItemResponse;

/**
 * 投递时间线（契约 JobApplicationTimeline）：面试轮次按轮次升序，复盘与改进动作按时间倒序；
 * offer 为该投递关联的最新 Offer。
 */
public record JobApplicationTimelineResponse(
        JobApplicationListItemResponse application,
        List<InterviewEventListItemResponse> events,
        List<ReviewSessionListItemResponse> reviews,
        Offer offer,
        List<ImprovementActionResponse> actions) {
}
