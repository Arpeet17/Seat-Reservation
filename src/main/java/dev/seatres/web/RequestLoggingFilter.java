package dev.seatres.web;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

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
import org.springframework.web.servlet.HandlerMapping;

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

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> timers = new ConcurrentHashMap<>();

    public RequestLoggingFilter(MeterRegistry registry) {
        this.registry = registry;
    }

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
            long elapsed = System.nanoTime() - start;
            long durationMs = elapsed / 1_000_000;
            recordHttpMetrics(req, res.getStatus(), elapsed);
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

    /** Route is the matched URI template (e.g. /shows/{showId}/reserve), never the raw path. */
    private void recordHttpMetrics(HttpServletRequest req, int status, long nanos) {
        Object pattern = req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = pattern != null ? pattern.toString() : "UNMATCHED";
        String method = req.getMethod();
        String statusClass = (status / 100) + "xx";
        counters.computeIfAbsent(method + ' ' + route + ' ' + status, k -> Counter.builder("http.requests")
                .tag("method", method).tag("route", route).tag("status", String.valueOf(status))
                .register(registry)).increment();
        timers.computeIfAbsent(method + ' ' + route + ' ' + statusClass, k -> Timer.builder("http.request.duration")
                .tag("method", method).tag("route", route).tag("status_class", statusClass)
                .publishPercentileHistogram().register(registry)).record(nanos, TimeUnit.NANOSECONDS);
    }

    private static boolean isQuietPath(String path) {
        return path.startsWith("/health") || path.equals("/metrics");
    }
}
