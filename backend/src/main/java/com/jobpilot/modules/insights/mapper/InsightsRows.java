package com.jobpilot.modules.insights.mapper;

import java.time.OffsetDateTime;
import lombok.Data;

/** 洞察聚合查询的行结构（只承载 SQL 聚合结果，不直接对外返回）。 */
public final class InsightsRows {

    private InsightsRows() {
    }

    @Data
    public static class TopicOutcomeRow {
        private String tag;
        private Long interviewCount;
        private Long passCount;
        private Long failCount;
        private Long pendingCount;
    }

    @Data
    public static class LossTagRow {
        private String tag;
        private Long tagCount;
        private OffsetDateTime lastSeenAt;
    }

    @Data
    public static class RoundRow {
        private String roundType;
        private Long total;
        private Long passCount;
        private Long failCount;
        private Long pendingCount;
    }

    @Data
    public static class CompanyRow {
        private Long companyId;
        private String companyName;
        private Long interviewCount;
        private Long passCount;
        private Long failCount;
        private Long pendingCount;
        private OffsetDateTime lastInterviewAt;
    }

    @Data
    public static class CompanyTagRow {
        private Long companyId;
        private String tag;
        private Long tagCount;
    }

    @Data
    public static class QuestionRow {
        private String question;
        private Long askedCount;
        private OffsetDateTime lastAskedAt;
    }

    @Data
    public static class FunnelRow {
        private Long applications;
        private Long interviews;
        private Long offers;
        private Long rejected;
        private Long withdrawn;
    }

    @Data
    public static class ResumeFunnelRow {
        private Long resumeId;
        private String fileName;
        private Long applications;
        private Long interviews;
        private Long offers;
        private Long passed;
        private Long rejected;
    }

    @Data
    public static class ReviewStatsRow {
        private Long totalReviews;
        private Long confirmedReviews;
    }

    /** 渠道 / 公司类型对比（FR8.4）：按投递来源聚合。 */
    @Data
    public static class FunnelGroupRow {
        private String label;
        private Long applications;
        private Long interviews;
        private Long offers;
    }

    @Data
    public static class ActionStatusRow {
        private String status;
        private Long actionCount;
    }

    @Data
    public static class StaleActionRow {
        private Long id;
        private String action;
        private String status;
        private OffsetDateTime createdAt;
    }

    /** 面试明细（FR5.2）：一条事件 + 最近一次复盘分析原文。 */
    @Data
    public static class InsightEventRow {
        private Long id;
        private Long applicationId;
        private Long companyId;
        private String companyName;
        private Long jobPositionId;
        private String jobTitle;
        private Integer roundNo;
        private String roundType;
        private String result;
        private OffsetDateTime interviewTime;
        private Long reviewSessionId;
        private String analysisJson;
    }
}
