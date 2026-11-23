package com.jobpilot.modules.application.dto;

import java.util.List;

import com.jobpilot.modules.offer.dto.Offer;
import com.jobpilot.modules.review.dto.InterviewEventListItemResponse;

/**
 * 投递时间线（契约 JobApplicationTimeline）：面试轮次按轮次升序；
 * 复盘与改进动作随 M3-b B2 / B3 接入，当前返回空数组；offer 为该投递关联的最新 Offer。
 */
public record JobApplicationTimelineResponse(
        JobApplicationListItemResponse application,
        List<InterviewEventListItemResponse> events,
        List<Object> reviews,
        Offer offer,
        List<Object> actions) {
}
