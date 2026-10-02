package dev.seatres.observability;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.seatres.auth.Principal;

/** On-demand reconciliation, used by the burst tool and by operators during an incident. */
@RestController
public class AdminController {

    private final SeatStateMonitor monitor;

    public AdminController(SeatStateMonitor monitor) {
        this.monitor = monitor;
    }

    @GetMapping("/admin/reconciliation")
    public Map<String, Object> reconciliation(Principal principal) {
        principal.requireAdmin();
        Map<String, Long> checks = monitor.reconcile();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", checks.values().stream().allMatch(v -> v == 0));
        body.put("checks", checks);
        return body;
    }

    /**
     * Recent structured log lines from this instance as NDJSON, newest last. Filter by request_id
     * (copy it from any error body or X-Request-Id header), level, or free text.
     */
    @GetMapping(value = "/admin/logs", produces = "application/x-ndjson")
    public String logs(Principal principal,
                       @RequestParam(defaultValue = "200") int limit,
                       @RequestParam(name = "request_id", required = false) String requestId,
                       @RequestParam(required = false) String level,
                       @RequestParam(required = false) String q) {
        principal.requireAdmin();
        int n = Math.max(1, Math.min(limit, 2000));
        List<String> lines = RecentLogsAppender.recent(n,
                requestId == null ? null : "\"request_id\":\"" + requestId + "\"",
                level == null ? null : "\"level\":\"" + level.toUpperCase(Locale.ROOT) + "\"",
                q);
        return lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
    }
}
