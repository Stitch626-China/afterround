package com.jobpilot.modules.llm.mapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jobpilot.modules.llm.entity.LlmCallLog;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** LLM 调用日志持久层；NFR6 成本统计用只读聚合查询，不修改日志。 */
public interface LlmCallLogMapper extends BaseMapper<LlmCallLog> {

    @Select("""
            SELECT COUNT(*) AS calls,
                   COUNT(*) FILTER (WHERE success) AS success_calls,
                   COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens,
                   COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
                   COALESCE(SUM(total_tokens), 0) AS total_tokens,
                   COALESCE(AVG(duration_ms), 0) AS avg_duration_ms
            FROM llm_call_logs
            WHERE created_at >= #{from}
            """)
    Map<String, Object> usageSummary(@Param("from") OffsetDateTime from);

    @Select("""
            SELECT scene,
                   COUNT(*) AS calls,
                   COUNT(*) FILTER (WHERE success) AS success_calls,
                   COALESCE(SUM(total_tokens), 0) AS total_tokens,
                   COALESCE(AVG(duration_ms), 0) AS avg_duration_ms
            FROM llm_call_logs
            WHERE created_at >= #{from}
            GROUP BY scene
            ORDER BY total_tokens DESC, calls DESC
            """)
    List<Map<String, Object>> usageByScene(@Param("from") OffsetDateTime from);

    @Select("""
            SELECT provider_key, model,
                   COUNT(*) AS calls,
                   COALESCE(SUM(total_tokens), 0) AS total_tokens
            FROM llm_call_logs
            WHERE created_at >= #{from}
            GROUP BY provider_key, model
            ORDER BY total_tokens DESC, calls DESC
            LIMIT 20
            """)
    List<Map<String, Object>> usageByModel(@Param("from") OffsetDateTime from);

    @Select("""
            SELECT to_char(created_at, 'YYYY-MM-DD') AS day,
                   COUNT(*) AS calls,
                   COALESCE(SUM(total_tokens), 0) AS total_tokens
            FROM llm_call_logs
            WHERE created_at >= #{from}
            GROUP BY day
            ORDER BY day
            """)
    List<Map<String, Object>> usageDaily(@Param("from") OffsetDateTime from);
}
