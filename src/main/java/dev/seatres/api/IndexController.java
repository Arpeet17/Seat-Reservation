package dev.seatres.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Landing response for GET / so a visitor to the bare URL sees what the service is and how to use it. */
@RestController
public class IndexController {

    @GetMapping("/")
    public Map<String, Object> index() {
        Map<String, Object> endpoints = new LinkedHashMap<>();
        endpoints.put("POST /auth/dev-token", "mint a demo token: {\"user_id\":\"alice\"} or {\"user_id\":\"admin\",\"role\":\"admin\"}");
        endpoints.put("POST /shows", "create a show (admin)");
        endpoints.put("GET /shows/{showId}", "show state, counts and per-seat status");
        endpoints.put("POST /shows/{showId}/reserve", "reserve seats: {\"seats\":[\"A1\"],\"idempotency_key\":\"...\"}");
        endpoints.put("GET /reservations/{id}", "your reservation");
        endpoints.put("POST /reservations/{id}/cancel", "cancel your reservation");
        endpoints.put("GET /health/live", "liveness");
        endpoints.put("GET /health/ready", "readiness (checks PostgreSQL)");
        endpoints.put("GET /metrics", "Prometheus metrics");
        endpoints.put("GET /admin/logs", "recent structured logs (admin)");
        endpoints.put("GET /admin/reconciliation", "invariant checks against the database (admin)");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "seat-reservation");
        body.put("docs", "https://github.com/Arpeet17/Seat-Reservation#readme");
        body.put("endpoints", endpoints);
        return body;
    }
}
