package com.jobpilot.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobpilot.common.web.TraceIdFilter;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class ResultTest {

    @AfterEach
    void cleanMdc() {
        MDC.remove(TraceIdFilter.MDC_KEY);
    }

    @Test
    @DisplayName("成功响应自动带上 MDC 中的 traceId")
    void okCarriesTraceIdFromMdc() {
        MDC.put(TraceIdFilter.MDC_KEY, "trace-abc");

        Result<String> result = Result.ok("payload");

        assertThat(result.code()).isZero();
        assertThat(result.message()).isEqualTo("ok");
        assertThat(result.data()).isEqualTo("payload");
        assertThat(result.traceId()).isEqualTo("trace-abc");
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("失败响应使用错误码与自定义消息，data 为空")
    void failUsesErrorCode() {
        Result<Void> result = Result.fail(ErrorCode.SESSION_SWITCH_FORBIDDEN, "任务执行中");

        assertThat(result.code()).isEqualTo(40901);
        assertThat(result.message()).isEqualTo("任务执行中");
        assertThat(result.data()).isNull();
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    @DisplayName("错误码的 HTTP 状态映射与契约一致")
    void errorCodeMapsToHttpStatus() {
        assertThat(ErrorCode.BAD_REQUEST.httpStatus().value()).isEqualTo(400);
        assertThat(ErrorCode.NOT_FOUND.httpStatus().value()).isEqualTo(404);
        assertThat(ErrorCode.SESSION_SWITCH_FORBIDDEN.httpStatus().value()).isEqualTo(409);
        assertThat(ErrorCode.SESSION_LOCKED.httpStatus().value()).isEqualTo(409);
        assertThat(ErrorCode.PAYLOAD_TOO_LARGE.httpStatus().value()).isEqualTo(413);
        assertThat(ErrorCode.UNSUPPORTED_MEDIA_TYPE.httpStatus().value()).isEqualTo(415);
        assertThat(ErrorCode.INTERNAL_ERROR.httpStatus().value()).isEqualTo(500);
    }

    @Test
    @DisplayName("分页参数规范化：页码缺省 1，条数缺省 20 且不超过 100")
    void pageNormalization() {
        assertThat(PageResult.normalizePage(null)).isEqualTo(1);
        assertThat(PageResult.normalizePage(0)).isEqualTo(1);
        assertThat(PageResult.normalizePage(3)).isEqualTo(3);

        assertThat(PageResult.normalizeSize(null)).isEqualTo(PageResult.DEFAULT_SIZE);
        assertThat(PageResult.normalizeSize(0)).isEqualTo(PageResult.DEFAULT_SIZE);
        assertThat(PageResult.normalizeSize(50)).isEqualTo(50);
        assertThat(PageResult.normalizeSize(999)).isEqualTo(PageResult.MAX_SIZE);
    }

    @Test
    @DisplayName("分页结果对空列表做兜底，避免前端拿到 null")
    void pageResultHandlesNullItems() {
        PageResult<String> page = new PageResult<>(null, 0, 1, 20);

        assertThat(page.items()).isNotNull().isEmpty();
        assertThat(PageResult.of(List.of("a"), 1, 1, 20).items()).containsExactly("a");
    }
}
