package dev.seatres.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes seat gauges and invariant checks computed from authoritative database state, never
 * from in-process counters (which would drift across instances and restarts).
 *
 * Each check is a single SQL statement, so it observes one consistent snapshot even while
 * reservations are committing.
 */
@Component
public class SeatStateMonitor {

    private static final Logger log = LoggerFactory.getLogger(SeatStateMonitor.class);

    /** name → SQL returning a single count that must be 0. */
    static final Map<String, String> CHECKS = new LinkedHashMap<>();

    static {
        CHECKS.put("taken_seats_vs_active_history",
                "SELECT abs((SELECT count(*) FROM seats WHERE status <> 'AVAILABLE') "
                        + "- (SELECT count(*) FROM reservation_seats WHERE active))");
        CHECKS.put("seat_owner_not_confirmed",
                "SELECT count(*) FROM seats s JOIN reservations r ON r.id = s.reservation_id WHERE r.status <> 'CONFIRMED'");
        CHECKS.put("reservation_seat_count_mismatch",
                "SELECT count(*) FROM reservations r WHERE r.status = 'CONFIRMED' AND r.seat_count <> "
                        + "(SELECT count(*) FROM seats s WHERE s.reservation_id = r.id)");
        CHECKS.put("quota_vs_reservations",
                "SELECT count(*) FROM user_show_quota q FULL JOIN "
                        + "(SELECT show_id, user_id, sum(seat_count) AS n FROM reservations WHERE status = 'CONFIRMED' "
                        + "GROUP BY show_id, user_id) a USING (show_id, user_id) "
                        + "WHERE coalesce(q.seats_reserved, 0) <> coalesce(a.n, 0)");
        CHECKS.put("quota_over_limit",
                "SELECT count(*) FROM user_show_quota q JOIN shows s ON s.id = q.show_id WHERE q.seats_reserved > s.per_user_limit");
        CHECKS.put("show_seat_total_mismatch",
                "SELECT count(*) FROM shows sh WHERE sh.total_seats <> (SELECT count(*) FROM seats s WHERE s.show_id = sh.id)");
        CHECKS.put("payment_mismatch",
                "SELECT count(*) FROM reservations r LEFT JOIN payments p ON p.reservation_id = r.id "
                        + "WHERE p.id IS NULL OR (r.status = 'CONFIRMED') <> (p.status = 'CAPTURED') OR p.amount_paise <> r.amount_paise");
    }

    private final JdbcTemplate jdbc;
    private final AtomicLong available = new AtomicLong();
    private final AtomicLong held = new AtomicLong();
    private final AtomicLong confirmed = new AtomicLong();
    private final AtomicLong mismatches = new AtomicLong();

    public SeatStateMonitor(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        Gauge.builder("seats.available", available, AtomicLong::get).description("Seats available, all shows").register(registry);
        Gauge.builder("seats.held", held, AtomicLong::get).description("Seats held, all shows").register(registry);
        Gauge.builder("seats.confirmed", confirmed, AtomicLong::get).description("Seats confirmed, all shows").register(registry);
        Gauge.builder("reconciliation.mismatches", mismatches, AtomicLong::get)
                .description("Rows violating a reconciliation invariant; must always be 0").register(registry);
    }

    @Scheduled(fixedDelayString = "${seatres.monitor.seat-gauge-interval-ms:10000}", initialDelay = 5000)
    public void refreshSeatGauges() {
        try {
            long a = 0, h = 0, c = 0;
            for (Map<String, Object> row : jdbc.queryForList("SELECT status, count(*) AS n FROM seats GROUP BY status")) {
                long n = ((Number) row.get("n")).longValue();
                switch ((String) row.get("status")) {
                    case "AVAILABLE" -> a = n;
                    case "HELD" -> h = n;
                    case "CONFIRMED" -> c = n;
                    default -> { }
                }
            }
            available.set(a);
            held.set(h);
            confirmed.set(c);
        } catch (Exception e) {
            log.warn("seat gauge refresh failed: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${seatres.monitor.reconciliation-interval-ms:30000}", initialDelay = 15000)
    public void reconcileScheduled() {
        try {
            reconcile();
        } catch (Exception e) {
            log.warn("reconciliation run failed: {}", e.getMessage());
        }
    }

    /** Runs every invariant check; returns name → violating row count. */
    public Map<String, Long> reconcile() {
        Map<String, Long> results = new LinkedHashMap<>();
        long total = 0;
        for (var check : CHECKS.entrySet()) {
            Long n = jdbc.queryForObject(check.getValue(), Long.class);
            long v = n == null ? 0 : n;
            results.put(check.getKey(), v);
            total += v;
        }
        mismatches.set(total);
        if (total > 0) {
            log.error("RECONCILIATION MISMATCH {}", results);
        }
        return results;
    }
}
