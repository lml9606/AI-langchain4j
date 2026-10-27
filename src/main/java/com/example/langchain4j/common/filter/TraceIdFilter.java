package com.example.langchain4j.common.filter;

import com.example.langchain4j.common.util.TraceIdUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * TraceId 过滤器：为每个 HTTP 请求生成/透传 traceId。
 * <p>
 * <ul>
 *   <li>上游已带 X-Trace-Id 则透传（跨服务串联），否则新生成；</li>
 *   <li>写入 SLF4J MDC，配合 logback pattern 中的 %X{traceId} 输出到每条日志；</li>
 *   <li>响应头回写 X-Trace-Id，客户端报障时可凭此定位日志；</li>
 *   <li>请求结束清理 MDC，防止线程池复用导致 traceId 串号。</li>
 * </ul>
 * 注意：MDC 是线程本地变量，图执行线程池中的日志由
 * {@code AsyncConfig} 的 TaskDecorator 负责传递 MDC 上下文。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER_TRACE_ID);
        String traceId = (incoming != null && !incoming.isBlank())
                ? incoming
                : TraceIdUtil.generate();
        TraceIdUtil.set(traceId);
        response.setHeader(HEADER_TRACE_ID, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            TraceIdUtil.clear();
        }
    }
}
