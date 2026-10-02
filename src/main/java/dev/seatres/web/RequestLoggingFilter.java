package dev.seatres.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Assigns a correlation id (honouring a well-formed inbound X-Request-Id), exposes it on the
 * response, and emits exactly one structured access-log line per request. Never logs headers, so
 * bearer tokens cannot leak into logs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger access = LoggerFactory.getLogger("access");
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String inbound = req.getHeader("X-Request-Id");
        String requestId = inbound != null && SAFE_ID.matcher(inbound).matches() ? inbound : UUID.randomUUID().toString();
        MDC.put(RequestContext.MDC_REQUEST_ID, requestId);
        res.setHeader("X-Request-Id", requestId);
        long start = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            if (!isQuietPath(req.getRequestURI()) || res.getStatus() >= 400) {
                access.info("request completed",
                        kv("method", req.getMethod()),
                        kv("path", req.getRequestURI()),
                        kv("status", res.getStatus()),
                        kv("duration_ms", durationMs),
                        kv("error_code", req.getAttribute(RequestContext.ATTR_ERROR_CODE)));
            }
            MDC.clear();
        }
    }

    private static boolean isQuietPath(String path) {
        return path.startsWith("/health") || path.equals("/metrics");
    }
}
