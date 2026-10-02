package dev.seatres.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Landing response for GET / so a visitor to the bare URL sees what the service is and how to use it. */
@RestController
public class IndexController {

    private static Map<String, Object> endpoint(String method, String path, String auth, String description,
                                                Object exampleBody) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("method", method);
        e.put("path", path);
        e.put("auth", auth);
        e.put("description", description);
        if (exampleBody != null) {
            e.put("example_body", exampleBody);
        }
        return e;
    }

    /** Insertion-ordered map so examples render with fields in a readable order. */
    private static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @GetMapping("/")
    public Map<String, Object> index() {
        List<Map<String, Object>> endpoints = List.of(
                endpoint("POST", "/auth/dev-token", "none", "mint a demo token; include role admin for an admin token",
                        ordered("user_id", "alice")),
                endpoint("POST", "/shows", "admin", "create a show",
                        ordered("name", "friday-night", "seats", List.of("A1", "A2", "A3"), "price_paise", 25000,
                                "per_user_limit", 4)),
                endpoint("GET", "/shows/{showId}", "none", "show state, counts and per-seat status", null),
                endpoint("POST", "/shows/{showId}/reserve", "user", "reserve seats (all or nothing)",
                        ordered("seats", List.of("A1"), "idempotency_key", "client-generated-key")),
                endpoint("GET", "/reservations/{id}", "owner", "your reservation", null),
                endpoint("POST", "/reservations/{id}/cancel", "owner", "cancel your reservation", null),
                endpoint("GET", "/health/live", "none", "liveness", null),
                endpoint("GET", "/health/ready", "none", "readiness (checks PostgreSQL)", null),
                endpoint("GET", "/metrics", "none", "Prometheus metrics", null),
                endpoint("GET", "/admin/logs", "admin", "recent structured logs (?request_id=, ?level=, ?q=, ?limit=)", null),
                endpoint("GET", "/admin/reconciliation", "admin", "invariant checks against the database", null));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "seat-reservation");
        body.put("docs", "https://github.com/Arpeet17/Seat-Reservation#readme");
        body.put("auth_header", "Authorization: Bearer <token from /auth/dev-token>");
        body.put("endpoints", endpoints);
        return body;
    }
}
