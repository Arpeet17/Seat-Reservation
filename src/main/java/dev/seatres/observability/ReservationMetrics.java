package dev.seatres.observability;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.springframework.stereotype.Component;

import dev.seatres.error.ErrorCode;

/**
 * Reservation business metrics. Labels are restricted to small closed sets (reason, outcome, type);
 * never user, show, reservation or idempotency-key ids. Meters are cached so the hot path does
 * not rebuild meter ids on every request.
 */
@Component
public class ReservationMetrics {

    private final MeterRegistry registry;
    private final Counter requests;
    private final Counter confirmed;
    private final Counter cancelled;
    private final ConcurrentHashMap<String, Counter> declined = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> errors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> dbFailures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> retries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> latency = new ConcurrentHashMap<>();

    public ReservationMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.requests = Counter.builder("reservation.requests").description("Reservation attempts received").register(registry);
        this.confirmed = Counter.builder("reservations.confirmed").description("Reservations newly confirmed").register(registry);
        this.cancelled = Counter.builder("reservations.cancelled")
                .description("Successful cancel calls, including idempotent repeats").register(registry);
        for (String reason : new String[]{"seat_taken", "per_user_limit", "idempotent_replay", "idempotency_conflict", "invalid_request"}) {
            declined(reason); // pre-register so every series exists at zero
        }
    }

    public void request() {
        requests.increment();
    }

    public void confirmed() {
        confirmed.increment();
    }

    public void cancelled() {
        cancelled.increment();
    }

    public void replayed() {
        declined("idempotent_replay").increment();
    }

    /** Classifies a failed reservation attempt as a domain decline or an error. */
    public void failed(ErrorCode code) {
        switch (code) {
            case SEAT_UNAVAILABLE -> declined("seat_taken").increment();
            case PER_USER_LIMIT_EXCEEDED -> declined("per_user_limit").increment();
            case IDEMPOTENCY_KEY_REUSED -> declined("idempotency_conflict").increment();
            default -> {
                if (code.status().is4xxClientError()) {
                    declined("invalid_request").increment();
                } else {
                    error(code.name().toLowerCase());
                }
            }
        }
    }

    public void error(String type) {
        errors.computeIfAbsent(type, t -> Counter.builder("reservation.errors").tag("type", t)
                .description("Reservation attempts that failed with 5xx").register(registry)).increment();
    }

    public void latency(String outcome, long nanos) {
        latency.computeIfAbsent(outcome, o -> Timer.builder("reservation.latency").tag("outcome", o)
                        .publishPercentileHistogram().description("End-to-end reservation handling time").register(registry))
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    /** A transaction rolled back by a transient lock failure and was retried internally. */
    public void retried(String type) {
        retries.computeIfAbsent(type, t -> Counter.builder("db.transaction.retries").tag("type", t)
                .description("Transactions retried after a transient lock failure").register(registry)).increment();
        dbFailure(type);
    }

    /** Counts database-level transaction failures by SQLSTATE class; called from the error handler. */
    public void dbFailure(Throwable e) {
        String type = dbFailureType(e);
        if (type != null) {
            dbFailure(type);
        }
    }

    private void dbFailure(String type) {
        dbFailures.computeIfAbsent(type, t -> Counter.builder("db.transaction.failures").tag("type", t)
                .description("Database transaction failures by type").register(registry)).increment();
    }

    private Counter declined(String reason) {
        return declined.computeIfAbsent(reason, r -> Counter.builder("reservations.declined").tag("reason", r)
                .description("Reservation attempts not resulting in a new reservation").register(registry));
    }

    public static String dbFailureType(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                String s = sql.getSQLState();
                if (s.equals("40P01")) return "deadlock";
                if (s.equals("40001")) return "serialization";
                if (s.equals("55P03")) return "lock_timeout";
                if (s.equals("57014")) return "statement_timeout";
                if (s.startsWith("08") || s.startsWith("57P")) return "connection";
            }
            if (t instanceof SQLTransientConnectionException) return "pool_timeout";
        }
        return null;
    }
}
