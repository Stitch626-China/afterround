package com.jobpilot.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.SecureRandom;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 链路追踪过滤器：为每个请求分配 traceId，写入 MDC 并回写到响应头。
 *
 * <p>约定：
 * <ul>
 *   <li>请求头携带 {@code X-Trace-Id} 时沿用（便于前端/网关串联），否则自动生成；</li>
 *   <li>traceId 会通过 {@link com.jobpilot.common.api.Result} 出现在响应体中；</li>
 *   <li>请求结束后清理 MDC，避免线程复用导致串号。</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** 统一响应头/请求头名称。 */
    public static final String HEADER = "X-Trace-Id";

    /** MDC 键名，日志 pattern 中使用同一键。 */
    public static final String MDC_KEY = "traceId";

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final int TRACE_ID_LENGTH = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(HEADER);
        if (!StringUtils.hasText(traceId)) {
            traceId = generateTraceId();
        }
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** 生成 16 位十六进制 traceId（64 bit 随机，单机足够避免碰撞）。 */
    static String generateTraceId() {
        char[] buf = new char[TRACE_ID_LENGTH];
        for (int i = 0; i < TRACE_ID_LENGTH; i++) {
            buf[i] = HEX[RANDOM.nextInt(HEX.length)];
        }
        return new String(buf);
    }
}
