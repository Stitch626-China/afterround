package com.jobpilot.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void cleanMdc() {
        MDC.remove(TraceIdFilter.MDC_KEY);
    }

    @Test
    @DisplayName("请求头未携带 traceId 时自动生成，并写入响应头与 MDC")
    void generatesTraceIdWhenHeaderMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ping");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcInsideChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> mdcInsideChain.set(MDC.get(TraceIdFilter.MDC_KEY)));

        String headerTraceId = response.getHeader(TraceIdFilter.HEADER);
        assertThat(headerTraceId).isNotBlank().hasSize(16);
        assertThat(mdcInsideChain.get()).isEqualTo(headerTraceId);
    }

    @Test
    @DisplayName("请求头已携带 traceId 时沿用，便于前端与网关串联")
    void reusesIncomingTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ping");
        request.addHeader(TraceIdFilter.HEADER, "given-trace-0001");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo("given-trace-0001");
    }

    @Test
    @DisplayName("请求结束后清理 MDC，避免线程复用串号")
    void clearsMdcAfterRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ping");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("生成的 traceId 为 16 位小写十六进制")
    void generatedTraceIdShape() {
        assertThat(TraceIdFilter.generateTraceId()).matches("[0-9a-f]{16}");
    }
}
