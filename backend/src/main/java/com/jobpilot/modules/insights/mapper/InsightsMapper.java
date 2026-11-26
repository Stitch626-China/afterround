package com.jobpilot.modules.insights.mapper;

import java.time.OffsetDateTime;
import java.util.List;

import com.jobpilot.modules.insights.mapper.InsightsRows.ActionStatusRow;
import com.jobpilot.modules.insights.mapper.InsightsRows.CompanyRow;
import com.jobpilot.modules.insights.mapper.InsightsRows.CompanyTagRow;
import com.jobpilot.modules.insights.mapper.InsightsRows.FunnelRow;
import com.jobpilot.modules.insights.mapper.InsightsRows.InsightEventRow;
import com.jobpilot.modules.insights.mapper.InsightsRows.LossTagRow;
import com.jobpilot.modules.insights.mapper.InsightsRows.QuestionRow;
import com.jobpilot.modules.insights.mapper.InsightsRows.ResumeFunnelRow;
import com.jobpilot.modules.insights.mapper.InsightsRows.ReviewStatsRow;
import com.jobpilot.modules.insights.mapper.InsightsRows.RoundRow;
import com.jobpilot.modules.insights.mapper.InsightsRows.StaleActionRow;
import com.jobpilot.modules.insights.mapper.InsightsRows.TopicOutcomeRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 跨场洞察聚合（FR5.5 ~ FR5.9）：全部在 PostgreSQL 内完成，只读取已确认复盘。
 * 时间窗口为 [from, to)；companyId / jobPositionId 为 null 时不限制。
 */
public interface InsightsMapper {

    @Select("""
            SELECT t->>'tag' AS tag,
                   COUNT(DISTINCT e.id) AS interview_count,
                   COUNT(DISTINCT e.id) FILTER (WHERE e.result = 'PASS') AS pass_count,
                   COUNT(DISTINCT e.id) FILTER (WHERE e.result = 'FAIL') AS fail_count,
                   COUNT(DISTINCT e.id) FILTER (WHERE e.result = 'PENDING') AS pending_count
            FROM review_sessions rs
            JOIN review_analyses ra ON ra.review_session_id = rs.id
            JOIN interview_events e ON e.id = rs.event_id
            JOIN applications a ON a.id = e.application_id
            LEFT JOIN job_positions jp ON jp.id = a.job_position_id
            CROSS JOIN LATERAL jsonb_array_elements(COALESCE(ra.analysis -> 'topics', '[]'::jsonb)) t
            WHERE rs.confirmed = TRUE
              AND e.interview_time >= #{from} AND e.interview_time < #{to}
              AND (CAST(#{companyId} AS BIGINT) IS NULL OR jp.company_id = #{companyId})
              AND (CAST(#{jobPositionId} AS BIGINT) IS NULL OR a.job_position_id = #{jobPositionId})
              AND COALESCE(t->>'tag', '') <> ''
            GROUP BY t->>'tag'
            ORDER BY fail_count DESC, interview_count DESC, tag
            """)
    List<TopicOutcomeRow> topicOutcomes(@Param("from") OffsetDateTime from,
                                        @Param("to") OffsetDateTime to,
                                        @Param("companyId") Long companyId,
                                        @Param("jobPositionId") Long jobPositionId);

    @Select("""
            SELECT t->>'tag' AS tag,
                   COUNT(DISTINCT e.id) AS tag_count,
                   MAX(e.interview_time) AS last_seen_at
            FROM review_sessions rs
            JOIN review_analyses ra ON ra.review_session_id = rs.id
            JOIN interview_events e ON e.id = rs.event_id
            JOIN applications a ON a.id = e.application_id
            LEFT JOIN job_positions jp ON jp.id = a.job_position_id
            CROSS JOIN LATERAL jsonb_array_elements(COALESCE(ra.analysis -> 'lossTags', '[]'::jsonb)) t
            WHERE rs.confirmed = TRUE
              AND e.interview_time >= #{from} AND e.interview_time < #{to}
              AND (CAST(#{companyId} AS BIGINT) IS NULL OR jp.company_id = #{companyId})
              AND (CAST(#{jobPositionId} AS BIGINT) IS NULL OR a.job_position_id = #{jobPositionId})
              AND COALESCE(t->>'tag', '') <> ''
            GROUP BY t->>'tag'
            ORDER BY tag_count DESC, tag
            """)
    List<LossTagRow> lossTagFrequency(@Param("from") OffsetDateTime from,
                                      @Param("to") OffsetDateTime to,
                                      @Param("companyId") Long companyId,
                                      @Param("jobPositionId") Long jobPositionId);

    @Select("""
            SELECT e.round_type AS round_type,
                   COUNT(*) AS total,
                   COUNT(*) FILTER (WHERE e.result = 'PASS') AS pass_count,
                   COUNT(*) FILTER (WHERE e.result = 'FAIL') AS fail_count,
                   COUNT(*) FILTER (WHERE e.result = 'PENDING') AS pending_count
            FROM interview_events e
            JOIN applications a ON a.id = e.application_id
            LEFT JOIN job_positions jp ON jp.id = a.job_position_id
            WHERE e.interview_time >= #{from} AND e.interview_time < #{to}
              AND (CAST(#{companyId} AS BIGINT) IS NULL OR jp.company_id = #{companyId})
              AND (CAST(#{jobPositionId} AS BIGINT) IS NULL OR a.job_position_id = #{jobPositionId})
            GROUP BY e.round_type
            ORDER BY total DESC, round_type
            """)
    List<RoundRow> roundPassRates(@Param("from") OffsetDateTime from,
                                  @Param("to") OffsetDateTime to,
                                  @Param("companyId") Long companyId,
                                  @Param("jobPositionId") Long jobPositionId);

    @Select("""
            SELECT jp.company_id AS company_id,
                   c.name AS company_name,
                   COUNT(*) AS interview_count,
                   COUNT(*) FILTER (WHERE e.result = 'PASS') AS pass_count,
                   COUNT(*) FILTER (WHERE e.result = 'FAIL') AS fail_count,
                   COUNT(*) FILTER (WHERE e.result = 'PENDING') AS pending_count,
                   MAX(e.interview_time) AS last_interview_at
            FROM interview_events e
            JOIN applications a ON a.id = e.application_id
            JOIN job_positions jp ON jp.id = a.job_position_id
            JOIN companies c ON c.id = jp.company_id
            WHERE e.interview_time >= #{from} AND e.interview_time < #{to}
              AND (CAST(#{companyId} AS BIGINT) IS NULL OR jp.company_id = #{companyId})
              AND (CAST(#{jobPositionId} AS BIGINT) IS NULL OR a.job_position_id = #{jobPositionId})
            GROUP BY jp.company_id, c.name
            ORDER BY interview_count DESC, company_name
            """)
    List<CompanyRow> companyProfiles(@Param("from") OffsetDateTime from,
                                     @Param("to") OffsetDateTime to,
                                     @Param("companyId") Long companyId,
                                     @Param("jobPositionId") Long jobPositionId);

    @Select("""
            WITH ranked AS (
                SELECT jp.company_id AS company_id,
                       t->>'tag' AS tag,
                       COUNT(DISTINCT e.id) AS tag_count,
                       ROW_NUMBER() OVER (
                           PARTITION BY jp.company_id
                           ORDER BY COUNT(DISTINCT e.id) DESC, t->>'tag'
                       ) AS rn
                FROM review_sessions rs
                JOIN review_analyses ra ON ra.review_session_id = rs.id
                JOIN interview_events e ON e.id = rs.event_id
                JOIN applications a ON a.id = e.application_id
                JOIN job_positions jp ON jp.id = a.job_position_id
                CROSS JOIN LATERAL jsonb_array_elements(COALESCE(ra.analysis -> 'topics', '[]'::jsonb)) t
                WHERE rs.confirmed = TRUE
                  AND e.interview_time >= #{from} AND e.interview_time < #{to}
                  AND (CAST(#{companyId} AS BIGINT) IS NULL OR jp.company_id = #{companyId})
                  AND (CAST(#{jobPositionId} AS BIGINT) IS NULL OR a.job_position_id = #{jobPositionId})
                  AND COALESCE(t->>'tag', '') <> ''
                GROUP BY jp.company_id, t->>'tag'
            )
            SELECT company_id, tag, tag_count FROM ranked WHERE rn <= 3 ORDER BY company_id, rn
            """)
    List<CompanyTagRow> companyTopicCounts(@Param("from") OffsetDateTime from,
                                           @Param("to") OffsetDateTime to,
                                           @Param("companyId") Long companyId,
                                           @Param("jobPositionId") Long jobPositionId);

    @Select("""
            WITH ranked AS (
                SELECT jp.company_id AS company_id,
                       t->>'tag' AS tag,
                       COUNT(DISTINCT e.id) AS tag_count,
                       ROW_NUMBER() OVER (
                           PARTITION BY jp.company_id
                           ORDER BY COUNT(DISTINCT e.id) DESC, t->>'tag'
                       ) AS rn
                FROM review_sessions rs
                JOIN review_analyses ra ON ra.review_session_id = rs.id
                JOIN interview_events e ON e.id = rs.event_id
                JOIN applications a ON a.id = e.application_id
                JOIN job_positions jp ON jp.id = a.job_position_id
                CROSS JOIN LATERAL jsonb_array_elements(COALESCE(ra.analysis -> 'lossTags', '[]'::jsonb)) t
                WHERE rs.confirmed = TRUE
                  AND e.interview_time >= #{from} AND e.interview_time < #{to}
                  AND (CAST(#{companyId} AS BIGINT) IS NULL OR jp.company_id = #{companyId})
                  AND (CAST(#{jobPositionId} AS BIGINT) IS NULL OR a.job_position_id = #{jobPositionId})
                  AND COALESCE(t->>'tag', '') <> ''
                GROUP BY jp.company_id, t->>'tag'
            )
            SELECT company_id, tag, tag_count FROM ranked WHERE rn <= 3 ORDER BY company_id, rn
            """)
    List<CompanyTagRow> companyLossTagCounts(@Param("from") OffsetDateTime from,
                                             @Param("to") OffsetDateTime to,
                                             @Param("companyId") Long companyId,
                                             @Param("jobPositionId") Long jobPositionId);

    @Select("""
            SELECT btrim(q->>'question') AS question,
                   COUNT(DISTINCT e.id) AS asked_count,
                   MAX(e.interview_time) AS last_asked_at
            FROM review_sessions rs
            JOIN review_analyses ra ON ra.review_session_id = rs.id
            JOIN interview_events e ON e.id = rs.event_id
            JOIN applications a ON a.id = e.application_id
            LEFT JOIN job_positions jp ON jp.id = a.job_position_id
            CROSS JOIN LATERAL jsonb_array_elements(COALESCE(ra.analysis -> 'questions', '[]'::jsonb)) q
            WHERE rs.confirmed = TRUE
              AND e.interview_time >= #{from} AND e.interview_time < #{to}
              AND (CAST(#{companyId} AS BIGINT) IS NULL OR jp.company_id = #{companyId})
              AND (CAST(#{jobPositionId} AS BIGINT) IS NULL OR a.job_position_id = #{jobPositionId})
              AND btrim(COALESCE(q->>'question', '')) <> ''
            GROUP BY btrim(q->>'question')
            ORDER BY asked_count DESC, last_asked_at DESC
            LIMIT 10
            """)
    List<QuestionRow> topQuestions(@Param("from") OffsetDateTime from,
                                   @Param("to") OffsetDateTime to,
                                   @Param("companyId") Long companyId,
                                   @Param("jobPositionId") Long jobPositionId);

    @Select("""
            SELECT COUNT(*) AS applications,
                   COUNT(*) FILTER (WHERE EXISTS (
                       SELECT 1 FROM interview_events e WHERE e.application_id = a.id
                   )) AS interviews,
                   COUNT(*) FILTER (WHERE EXISTS (
                       SELECT 1 FROM offers o WHERE o.application_id = a.id
                   )) AS offers,
                   COUNT(*) FILTER (WHERE a.status = 'REJECTED') AS rejected,
                   COUNT(*) FILTER (WHERE a.status = 'WITHDRAWN') AS withdrawn
            FROM applications a
            LEFT JOIN job_positions jp ON jp.id = a.job_position_id
            WHERE a.applied_at >= #{from} AND a.applied_at < #{to}
              AND (CAST(#{companyId} AS BIGINT) IS NULL OR jp.company_id = #{companyId})
              AND (CAST(#{jobPositionId} AS BIGINT) IS NULL OR a.job_position_id = #{jobPositionId})
            """)
    FunnelRow funnel(@Param("from") OffsetDateTime from,
                     @Param("to") OffsetDateTime to,
                     @Param("companyId") Long companyId,
                     @Param("jobPositionId") Long jobPositionId);

    @Select("""
            SELECT a.resume_id AS resume_id,
                   r.file_name AS file_name,
                   COUNT(*) AS applications,
                   COUNT(*) FILTER (WHERE EXISTS (
                       SELECT 1 FROM interview_events e WHERE e.application_id = a.id
                   )) AS interviews,
                   COUNT(*) FILTER (WHERE EXISTS (
                       SELECT 1 FROM offers o WHERE o.application_id = a.id
                   )) AS offers,
                   COUNT(*) FILTER (WHERE EXISTS (
                       SELECT 1 FROM interview_events e WHERE e.application_id = a.id AND e.result = 'PASS'
                   )) AS passed,
                   COUNT(*) FILTER (WHERE EXISTS (
                       SELECT 1 FROM interview_events e WHERE e.application_id = a.id AND e.result = 'FAIL'
                   )) AS rejected
            FROM applications a
            LEFT JOIN resumes r ON r.id = a.resume_id
            LEFT JOIN job_positions jp ON jp.id = a.job_position_id
            WHERE a.applied_at >= #{from} AND a.applied_at < #{to}
              AND (CAST(#{companyId} AS BIGINT) IS NULL OR jp.company_id = #{companyId})
              AND (CAST(#{jobPositionId} AS BIGINT) IS NULL OR a.job_position_id = #{jobPositionId})
            GROUP BY a.resume_id, r.file_name
            ORDER BY applications DESC, a.resume_id
            """)
    List<ResumeFunnelRow> resumeFunnel(@Param("from") OffsetDateTime from,
                                       @Param("to") OffsetDateTime to,
                                       @Param("companyId") Long companyId,
                                       @Param("jobPositionId") Long jobPositionId);

    @Select("""
            SELECT COUNT(*) AS total_reviews,
                   COUNT(*) FILTER (WHERE rs.confirmed) AS confirmed_reviews
            FROM review_sessions rs
            JOIN interview_events e ON e.id = rs.event_id
            JOIN applications a ON a.id = e.application_id
            LEFT JOIN job_positions jp ON jp.id = a.job_position_id
            WHERE e.interview_time >= #{from} AND e.interview_time < #{to}
              AND (CAST(#{companyId} AS BIGINT) IS NULL OR jp.company_id = #{companyId})
              AND (CAST(#{jobPositionId} AS BIGINT) IS NULL OR a.job_position_id = #{jobPositionId})
            """)
    ReviewStatsRow reviewStats(@Param("from") OffsetDateTime from,
                               @Param("to") OffsetDateTime to,
                               @Param("companyId") Long companyId,
                               @Param("jobPositionId") Long jobPositionId);

    @Select("""
            SELECT status, COUNT(*) AS action_count
            FROM improvement_actions
            GROUP BY status
            """)
    List<ActionStatusRow> actionStatusCounts();

    @Select("""
            SELECT id, action, status, created_at
            FROM improvement_actions
            WHERE status IN ('TODO', 'IN_PROGRESS') AND created_at < #{staleBefore}
            ORDER BY created_at ASC
            LIMIT 5
            """)
    List<StaleActionRow> staleActions(@Param("staleBefore") OffsetDateTime staleBefore);

    @Select("""
            SELECT e.id AS id,
                   e.application_id AS application_id,
                   jp.company_id AS company_id,
                   c.name AS company_name,
                   jp.id AS job_position_id,
                   jp.title AS job_title,
                   e.round_no AS round_no,
                   e.round_type AS round_type,
                   e.result AS result,
                   e.interview_time AS interview_time,
                   rs.id AS review_session_id,
                   CAST(ra.analysis AS TEXT) AS analysis_json
            FROM interview_events e
            JOIN applications a ON a.id = e.application_id
            JOIN job_positions jp ON jp.id = a.job_position_id
            LEFT JOIN companies c ON c.id = jp.company_id
            LEFT JOIN review_sessions rs ON rs.event_id = e.id
            LEFT JOIN review_analyses ra ON ra.review_session_id = rs.id
            WHERE e.interview_time >= #{from} AND e.interview_time < #{to}
              AND (CAST(#{companyId} AS BIGINT) IS NULL OR jp.company_id = #{companyId})
              AND (CAST(#{jobPositionId} AS BIGINT) IS NULL OR a.job_position_id = #{jobPositionId})
              AND (CAST(#{result} AS TEXT) IS NULL OR e.result = #{result})
              AND (CAST(#{roundType} AS TEXT) IS NULL OR e.round_type = #{roundType})
              AND (CAST(#{topic} AS TEXT) IS NULL OR EXISTS (
                  SELECT 1 FROM jsonb_array_elements(COALESCE(ra.analysis -> 'topics', '[]'::jsonb)) t
                  WHERE t->>'tag' = #{topic}
              ))
            ORDER BY e.interview_time DESC, e.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<InsightEventRow> interviewEvents(@Param("from") OffsetDateTime from,
                                          @Param("to") OffsetDateTime to,
                                          @Param("companyId") Long companyId,
                                          @Param("jobPositionId") Long jobPositionId,
                                          @Param("result") String result,
                                          @Param("roundType") String roundType,
                                          @Param("topic") String topic,
                                          @Param("offset") int offset,
                                          @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*)
            FROM interview_events e
            JOIN applications a ON a.id = e.application_id
            JOIN job_positions jp ON jp.id = a.job_position_id
            LEFT JOIN review_sessions rs ON rs.event_id = e.id
            LEFT JOIN review_analyses ra ON ra.review_session_id = rs.id
            WHERE e.interview_time >= #{from} AND e.interview_time < #{to}
              AND (CAST(#{companyId} AS BIGINT) IS NULL OR jp.company_id = #{companyId})
              AND (CAST(#{jobPositionId} AS BIGINT) IS NULL OR a.job_position_id = #{jobPositionId})
              AND (CAST(#{result} AS TEXT) IS NULL OR e.result = #{result})
              AND (CAST(#{roundType} AS TEXT) IS NULL OR e.round_type = #{roundType})
              AND (CAST(#{topic} AS TEXT) IS NULL OR EXISTS (
                  SELECT 1 FROM jsonb_array_elements(COALESCE(ra.analysis -> 'topics', '[]'::jsonb)) t
                  WHERE t->>'tag' = #{topic}
              ))
            """)
    long interviewEventCount(@Param("from") OffsetDateTime from,
                             @Param("to") OffsetDateTime to,
                             @Param("companyId") Long companyId,
                             @Param("jobPositionId") Long jobPositionId,
                             @Param("result") String result,
                             @Param("roundType") String roundType,
                             @Param("topic") String topic);
}
