package dev.seatres.reservation;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import dev.seatres.auth.Principal;
import dev.seatres.error.ApiException;
import dev.seatres.error.ErrorCode;
import dev.seatres.observability.ReservationMetrics;
import dev.seatres.reservation.ReservationRepository.IdempotencyRecord;
import dev.seatres.reservation.ReservationRepository.Precheck;
import dev.seatres.reservation.ReservationRepository.ReservationRow;
import dev.seatres.reservation.ReservationRepository.SeatState;
import dev.seatres.show.ShowRepository.ShowRow;
import dev.seatres.show.ShowService;

/**
 * Reservation in two stages.
 *
 * <p><b>Stage A — pre-check</b> (plain autocommit reads, no locks). Exists purely to keep the
 * losers of a hot-seat storm out of the lock queue. It may only <i>reject</i> or <i>replay</i>,
 * never grant: a rejection is correct as of the instant of the read, so it is linearizable.
 * Seats, quota and idempotency key are read in a single statement, hence a single snapshot: if
 * a seat or the quota reflects a committed reservation, that reservation's idempotency row is in
 * the same snapshot, so a client retry is replayed rather than falsely rejected.
 *
 * <p><b>Stage B — the authoritative transaction</b> (READ COMMITTED). Lock order is global:
 * idempotency key → seats (ascending id) → user quota row → fresh inserts. See WRITEUP.md for the
 * full interleaving analysis.
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    public record ReserveResult(ReservationView reservation, int status, boolean replayed) {
    }

    /** Signals, from inside the transaction, that (user, key) was already committed by someone else. */
    private static final class IdempotencyKeyTaken extends RuntimeException {
        IdempotencyKeyTaken() {
            super(null, null, false, false);
        }
    }

    private static final int MAX_ATTEMPTS = 3;

    private final ReservationRepository repo;
    private final ShowService shows;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final ReservationMetrics metrics;
    private final int lockTimeoutMs;
    private final int statementTimeoutMs;
    private final int maxSeatsPerRequest;

    public ReservationService(ReservationRepository repo, ShowService shows, PlatformTransactionManager txManager,
                              ObjectMapper json, ReservationMetrics metrics,
                              @Value("${seatres.db.lock-timeout-ms}") int lockTimeoutMs,
                              @Value("${seatres.db.statement-timeout-ms}") int statementTimeoutMs,
                              @Value("${seatres.limits.max-seats-per-request}") int maxSeatsPerRequest) {
        this.repo = repo;
        this.shows = shows;
        this.json = json;
        this.metrics = metrics;
        this.lockTimeoutMs = lockTimeoutMs;
        this.statementTimeoutMs = statementTimeoutMs;
        this.maxSeatsPerRequest = maxSeatsPerRequest;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public ReserveResult reserve(Principal principal, UUID showId, List<String> requestedSeats, String idemKey) {
        String userId = principal.userId();
        List<String> labels = normalise(requestedSeats);
        ShowRow show = shows.require(showId);
        byte[] hash = RequestHasher.hash(showId, labels);
        return withContentionRetry(() -> attemptReserve(userId, show, labels, hash, idemKey));
    }

    /**
     * Retries an attempt whose transaction was rolled back by a transient lock failure
     * (lock_timeout, deadlock, serialization). Safe by construction: the rollback discarded
     * everything, including the idempotency claim, so a new attempt starts from a clean slate.
     * Never retries OUTCOME_UNKNOWN (commit may have happened) or any domain outcome.
     */
    private <T> T withContentionRetry(Supplier<T> attempt) {
        for (int i = 1; ; i++) {
            try {
                return attempt.get();
            } catch (ApiException e) {
                throw e;
            } catch (RuntimeException e) {
                String type = ReservationMetrics.dbFailureType(e);
                boolean transientLock = "lock_timeout".equals(type) || "deadlock".equals(type) || "serialization".equals(type);
                if (!transientLock || i >= MAX_ATTEMPTS) {
                    throw e;
                }
                metrics.retried(type);
                log.warn("transient {} on attempt {}, retrying", type, i);
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextLong(10, 50) * i);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private ReserveResult attemptReserve(String userId, ShowRow show, List<String> labels, byte[] hash, String idemKey) {
        UUID showId = show.id();
        int n = labels.size();

        // ---------------- Stage A: non-authoritative pre-check (reject or replay only) ----------------
        Precheck pre = repo.precheck(showId, labels, userId, idemKey);
        if (pre.prior().isPresent()) {
            return replayOrConflict(pre.prior().get(), hash);
        }
        if (pre.seatsFound() != n) {
            throw new ApiException(ErrorCode.UNKNOWN_SEAT, "one or more seats do not exist in this show");
        }
        if (n > show.perUserLimit() || pre.quotaUsed() + n > show.perUserLimit()) {
            throw perUserLimit(show);
        }
        if (pre.seatsTaken() > 0) {
            throw seatUnavailable();
        }

        // ---------------- Stage B: authoritative transaction ----------------
        UUID reservationId = UUID.randomUUID();
        AtomicBoolean bodyCompleted = new AtomicBoolean(false);
        try {
            return tx.execute(status -> {
                repo.setLocalTimeouts(lockTimeoutMs, statementTimeoutMs);

                // B1. Claim the key first, so concurrent duplicates serialise here (and are answered
                //     with a replay) instead of queueing on seat locks and being told "seat taken".
                if (!repo.claimIdempotencyKey(userId, idemKey, hash, showId, reservationId)) {
                    throw new IdempotencyKeyTaken();
                }

                // B2. Lock seats in ascending id order; decide on the locked, latest-committed rows.
                List<SeatState> locked = repo.lockSeats(showId, labels);
                if (locked.size() != n) {
                    throw new ApiException(ErrorCode.UNKNOWN_SEAT, "one or more seats do not exist in this show");
                }
                if (locked.stream().anyMatch(s -> !"AVAILABLE".equals(s.status()))) {
                    throw seatUnavailable();     // all-or-nothing: rollback, nothing was written
                }

                // B3. Atomic per-user limit check-and-increment on the user's quota row.
                if (!repo.tryIncrementQuota(showId, userId, n, show.perUserLimit())) {
                    throw perUserLimit(show);
                }

                // B4-B8. Write the reservation, flip the seats, record history, payment and response.
                long amount = Math.multiplyExact(show.pricePaise(), (long) n);
                Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
                repo.insertReservation(new ReservationRow(reservationId, showId, userId, "CONFIRMED", n, amount, now, null));
                List<Long> seatIds = locked.stream().map(SeatState::id).toList();
                int flipped = repo.confirmSeats(seatIds, reservationId);
                if (flipped != n) {
                    // Impossible while we hold the row locks; refuse to commit if it ever happens.
                    throw new IllegalStateException("seat state changed under lock: expected " + n + ", updated " + flipped);
                }
                repo.insertReservationSeats(reservationId, seatIds);
                repo.insertPayment(UUID.randomUUID(), reservationId, amount);

                ReservationView view = new ReservationView(reservationId, showId, userId,
                        locked.stream().map(SeatState::label).toList(), amount, "confirmed", now, null);
                repo.storeIdempotentResponse(userId, idemKey, 201, toJson(view));
                bodyCompleted.set(true);
                return new ReserveResult(view, 201, false);
            });
        } catch (IdempotencyKeyTaken e) {
            // Our transaction rolled back. The key's owner has committed, so a fresh statement sees it.
            return repo.readIdempotency(userId, idemKey)
                    .map(r -> replayOrConflict(r, hash))
                    .orElseThrow(() -> new ApiException(ErrorCode.CONTENTION_TIMEOUT,
                            "idempotency key in flight, retry with the same key"));
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            if (bodyCompleted.get()) {
                // Failure during COMMIT: the transaction may or may not have been applied. The only
                // safe client action is to retry with the same idempotency key, which replays if it did.
                log.error("commit outcome unknown for reservation {}", reservationId, e);
                throw new ApiException(ErrorCode.OUTCOME_UNKNOWN,
                        "reservation outcome unknown, retry with the same idempotency key");
            }
            throw e;
        }
    }

    /**
     * CONFIRMED → CANCELLED, and each owned seat CONFIRMED → AVAILABLE, in one transaction.
     *
     * Lock order: reservation row → its seats (ascending id) → user quota row. The reserve path
     * never locks an existing reservation row, and both paths take seats in ascending id order and
     * the quota row last, so the two paths cannot form a wait cycle.
     *
     * Non-owners get 404, not 403, so reservation ids cannot be probed for existence. Cancelling
     * an already-cancelled reservation is an idempotent 200.
     */
    public ReservationView cancel(Principal principal, UUID reservationId) {
        // Lock-free fast path: CANCELLED is terminal, so a read that sees it can never be stale in
        // a way that matters. Repeat cancels return without joining the row-lock queue.
        ReservationRow seen = repo.findReservation(reservationId)
                .filter(row -> row.userId().equals(principal.userId()))
                .orElseThrow(ReservationService::reservationNotFound);
        if ("CANCELLED".equals(seen.status())) {
            return toView(seen, repo.seatLabelsOf(seen.id()));
        }
        return withContentionRetry(() -> tx.execute(status -> {
            repo.setLocalTimeouts(lockTimeoutMs, statementTimeoutMs);
            ReservationRow r = repo.lockReservation(reservationId)
                    .filter(row -> row.userId().equals(principal.userId()))
                    .orElseThrow(ReservationService::reservationNotFound);
            List<String> labels = repo.seatLabelsOf(r.id());
            if ("CANCELLED".equals(r.status())) {
                return toView(r, labels);
            }

            repo.lockSeatsOwnedBy(r.id());
            // WHERE reservation_id = :r — can only ever free seats this reservation still owns.
            int released = repo.releaseSeats(r.id());
            if (released != r.seatCount()) {
                throw new IllegalStateException("reservation " + r.id() + " owns " + released
                        + " seats, expected " + r.seatCount());
            }
            repo.deactivateReservationSeats(r.id());
            if (repo.decrementQuota(r.showId(), r.userId(), r.seatCount()) != 1) {
                throw new IllegalStateException("quota row missing for confirmed reservation " + r.id());
            }
            repo.refundPayment(r.id());
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            repo.markCancelled(r.id(), now);
            return toView(new ReservationRow(r.id(), r.showId(), r.userId(), "CANCELLED", r.seatCount(),
                    r.amountPaise(), r.createdAt(), now), labels);
        }));
    }

    public ReservationView get(Principal principal, UUID reservationId) {
        ReservationRow r = repo.findReservation(reservationId)
                .filter(row -> row.userId().equals(principal.userId()))
                .orElseThrow(ReservationService::reservationNotFound);
        return toView(r, repo.seatLabelsOf(r.id()));
    }

    // ------------------------------------------------------------------------ helpers

    private List<String> normalise(List<String> seats) {
        if (seats.size() > maxSeatsPerRequest) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "at most " + maxSeatsPerRequest + " seats per request");
        }
        Set<String> unique = new HashSet<>();
        for (String s : seats) {
            if (!unique.add(s)) {
                throw new ApiException(ErrorCode.DUPLICATE_SEAT, "seat requested more than once: " + s);
            }
        }
        return seats.stream().sorted().toList();
    }

    private ReserveResult replayOrConflict(IdempotencyRecord record, byte[] hash) {
        if (!RequestHasher.same(record.requestHash(), hash)) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "idempotency key was already used with a different request");
        }
        try {
            return new ReserveResult(json.readValue(record.responseBody(), ReservationView.class),
                    record.responseStatus(), true);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("corrupt stored idempotent response", e);
        }
    }

    static ReservationView toView(ReservationRow r, List<String> labels) {
        return new ReservationView(r.id(), r.showId(), r.userId(), labels, r.amountPaise(),
                r.status().toLowerCase(java.util.Locale.ROOT), r.createdAt(), r.cancelledAt());
    }

    private String toJson(ReservationView view) {
        try {
            return json.writeValueAsString(view);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException seatUnavailable() {
        return new ApiException(ErrorCode.SEAT_UNAVAILABLE, "one or more requested seats are unavailable");
    }

    private static ApiException perUserLimit(ShowRow show) {
        return new ApiException(ErrorCode.PER_USER_LIMIT_EXCEEDED,
                "per-user limit of " + show.perUserLimit() + " seats for this show would be exceeded");
    }

    static ApiException reservationNotFound() {
        return new ApiException(ErrorCode.RESERVATION_NOT_FOUND, "reservation not found");
    }
}
