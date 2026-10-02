package dev.seatres.observability;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
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
}
