package com.jobpilot.modules.llm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.jobpilot.common.api.ErrorCode;
import com.jobpilot.common.exception.BizException;
import com.jobpilot.modules.llm.dto.LlmUsageResponse;
import com.jobpilot.modules.llm.mapper.LlmCallLogMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** token 成本统计（NFR6）：聚合映射、场景中文名、空数据比例为 null、统计窗口校验。 */
@ExtendWith(MockitoExtension.class)
class LlmUsageServiceTest {

    @Mock
    private LlmCallLogMapper logMapper;

    private LlmUsageService service() {
        return new LlmUsageService(logMapper);
    }

    @Test
    void aggregatesSummaryScenesModelsAndDaily() {
        when(logMapper.usageSummary(any())).thenReturn(Map.of("calls", 4L, "success_calls", 3L,
                "prompt_tokens", 100L, "completion_tokens", 50L, "total_tokens", 150L, "avg_duration_ms", 820L));
        when(logMapper.usageByScene(any())).thenReturn(List.of(
                Map.of("scene", "RESUME_ANALYSIS", "calls", 3L, "success_calls", 2L,
                        "total_tokens", 120L, "avg_duration_ms", 900L),
                Map.of("scene", "CUSTOM_SCENE", "calls", 1L, "success_calls", 1L,
                        "total_tokens", 30L, "avg_duration_ms", 100L),
                Map.of()));
        when(logMapper.usageByModel(any())).thenReturn(List.of(
                Map.of("provider_key", "gw", "model", "qwen", "calls", 4L, "total_tokens", 150L)));
        when(logMapper.usageDaily(any())).thenReturn(List.of(
                Map.of("day", "2026-11-26", "calls", 4L, "total_tokens", 150L)));

        LlmUsageResponse usage = service().usage(30);

        assertThat(usage.days()).isEqualTo(30);
        assertThat(usage.summary().calls()).isEqualTo(4);
        assertThat(usage.summary().successRate()).isEqualTo(0.75);
        assertThat(usage.summary().promptTokens()).isEqualTo(100);
        assertThat(usage.summary().completionTokens()).isEqualTo(50);
        assertThat(usage.summary().totalTokens()).isEqualTo(150);
        assertThat(usage.summary().avgDurationMs()).isEqualTo(820);
        assertThat(usage.scenes()).extracting(LlmUsageResponse.Scene::label)
                .containsExactly("简历分析", "CUSTOM_SCENE", "未知场景");
        assertThat(usage.scenes().get(0).successRate()).isEqualTo(0.6667);
        assertThat(usage.scenes().get(0).avgDurationMs()).isEqualTo(900);
        assertThat(usage.models()).extracting(LlmUsageResponse.Model::providerKey).containsExactly("gw");
        assertThat(usage.models().get(0).totalTokens()).isEqualTo(150);
        assertThat(usage.daily()).extracting(LlmUsageResponse.Day::date).containsExactly("2026-11-26");
    }

    @Test
    void emptyWindowDefaultsToThirtyDaysWithNullRates() {
        when(logMapper.usageSummary(any())).thenReturn(null);
        when(logMapper.usageByScene(any())).thenReturn(List.of());
        when(logMapper.usageByModel(any())).thenReturn(List.of());
        when(logMapper.usageDaily(any())).thenReturn(List.of());

        LlmUsageResponse usage = service().usage(null);

        assertThat(usage.days()).isEqualTo(30);
        assertThat(usage.summary().calls()).isZero();
        assertThat(usage.summary().successRate()).isNull();
        assertThat(usage.summary().totalTokens()).isZero();
        assertThat(usage.scenes()).isEmpty();
        assertThat(usage.models()).isEmpty();
        assertThat(usage.daily()).isEmpty();
    }

    @Test
    void rejectsWindowOutsideRange() {
        assertThatThrownBy(() -> service().usage(6))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).errorCode())
                .isEqualTo(ErrorCode.BAD_REQUEST);
        assertThatThrownBy(() -> service().usage(366)).isInstanceOf(BizException.class);
    }
}
