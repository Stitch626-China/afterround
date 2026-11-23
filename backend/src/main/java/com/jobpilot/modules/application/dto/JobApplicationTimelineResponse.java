package com.jobpilot.modules.application.dto;

import java.util.List;

import com.jobpilot.modules.offer.dto.Offer;

/**
 * 投递时间线（契约 JobApplicationTimeline）：事件 / 复盘 / 改进动作在 M3-b / M3-c 接入，
 * 当前返回空数组；offer 为该投递关联的最新 Offer。
 */
public record JobApplicationTimelineResponse(
        JobApplicationListItemResponse application,
        List<Object> events,
        List<Object> reviews,
        Offer offer,
        List<Object> actions) {
}
