package dev.seatres.reservation;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Every statement on the reservation path, written out explicitly so the locking behaviour is
 * reviewable. Methods marked "LOCKS" must only be called inside a transaction.
 */
@Repository
public class ReservationRepository {

    public record SeatState(long id, String label, String status, UUID reservationId) {
    }

    public record IdempotencyRecord(byte[] requestHash, UUID reservationId, Integer responseStatus, String responseBody) {
    }

    public record ReservationRow(UUID id, UUID showId, String userId, String status, int seatCount, long amountPaise,
                                 Instant createdAt, Instant cancelledAt) {
    }

    private final JdbcTemplate jdbc;

    public ReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- transaction settings

    /** Bounds every lock wait and statement inside the current transaction. */
    public void setLocalTimeouts(int lockTimeoutMs, int statementTimeoutMs) {
        jdbc.execute("SET LOCAL lock_timeout = '" + lockTimeoutMs + "ms'");
        jdbc.execute("SET LOCAL statement_timeout = '" + statementTimeoutMs + "ms'");
    }

    // ---------------------------------------------------------------- non-locking reads (pre-check)

    public List<SeatState> readSeats(UUID showId, Collection<String> labels) {
        return seatQuery("SELECT id, label, status, reservation_id FROM seats WHERE show_id = ? AND label = ANY(?) ORDER BY id",
                showId, labels);
    }

    public int readQuota(UUID showId, String userId) {
        List<Integer> r = jdbc.queryForList("SELECT seats_reserved FROM user_show_quota WHERE show_id = ? AND user_id = ?",
                Integer.class, showId, userId);
        return r.isEmpty() ? 0 : r.get(0);
    }

    public Optional<IdempotencyRecord> readIdempotency(String userId, String key) {
        return jdbc.query("SELECT request_hash, reservation_id, response_status, response_body::text AS body "
                        + "FROM idempotency_keys WHERE user_id = ? AND idem_key = ?",
                (rs, i) -> new IdempotencyRecord(rs.getBytes("request_hash"), rs.getObject("reservation_id", UUID.class),
                        (Integer) rs.getObject("response_status"), rs.getString("body")),
                userId, key).stream().findFirst();
    }

    // ---------------------------------------------------------------- reserve transaction

    /**
     * LOCKS. Claims (user, key). Returns false if the key already exists. If another transaction
     * holds an uncommitted insert of the same key, this statement blocks on the unique index until
     * that transaction ends: on commit we get the conflict (false), on rollback our insert proceeds.
     */
    public boolean claimIdempotencyKey(String userId, String key, byte[] hash, UUID showId, UUID reservationId) {
        return jdbc.update("INSERT INTO idempotency_keys (user_id, idem_key, request_hash, show_id, reservation_id) "
                        + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (user_id, idem_key) DO NOTHING",
                userId, key, hash, showId, reservationId) == 1;
    }

    /**
     * LOCKS. Row-locks the requested seats in ascending primary-key order (LockRows runs above the
     * Sort node, so locks are taken in ORDER BY order). A uniform global order means no two
     * transactions can each hold a seat the other is waiting for: no deadlock cycles.
     *
     * Deliberately no "AND status = 'AVAILABLE'" filter: we lock every requested row and inspect
     * the status of the locked, latest-committed version in code.
     */
    public List<SeatState> lockSeats(UUID showId, Collection<String> labels) {
        return seatQuery("SELECT id, label, status, reservation_id FROM seats WHERE show_id = ? AND label = ANY(?) "
                + "ORDER BY id FOR UPDATE", showId, labels);
    }

    /**
     * LOCKS. Atomic check-and-increment of the per-user counter. ON CONFLICT DO UPDATE takes the
     * row lock and evaluates the WHERE clause against the latest committed value, so concurrent
     * requests from one user are serialised on this row and each sees the previous winner's
     * increment. Returns false when the increment would exceed the limit.
     *
     * The INSERT branch has no WHERE clause; callers must have checked seatCount <= limit already
     * (shows are immutable, so that check is stable).
     */
    public boolean tryIncrementQuota(UUID showId, String userId, int seatCount, int limit) {
        return !jdbc.queryForList("INSERT INTO user_show_quota (show_id, user_id, seats_reserved) VALUES (?, ?, ?) "
                        + "ON CONFLICT (show_id, user_id) DO UPDATE "
                        + "SET seats_reserved = user_show_quota.seats_reserved + EXCLUDED.seats_reserved "
                        + "WHERE user_show_quota.seats_reserved + EXCLUDED.seats_reserved <= ? "
                        + "RETURNING seats_reserved",
                Integer.class, showId, userId, seatCount, limit).isEmpty();
    }

    public void insertReservation(ReservationRow r) {
        jdbc.update("INSERT INTO reservations (id, show_id, user_id, status, seat_count, amount_paise, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                r.id(), r.showId(), r.userId(), r.status(), r.seatCount(), r.amountPaise(), Timestamp.from(r.createdAt()));
    }

    /** Guarded by status = 'AVAILABLE' as a second line of defence; caller asserts the row count. */
    public int confirmSeats(List<Long> seatIds, UUID reservationId) {
        return jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("UPDATE seats SET status = 'CONFIRMED', reservation_id = ?, "
                    + "updated_at = now() WHERE id = ANY(?) AND status = 'AVAILABLE'");
            ps.setObject(1, reservationId);
            ps.setArray(2, con.createArrayOf("bigint", seatIds.toArray()));
            return ps;
        });
    }

    public void insertReservationSeats(UUID reservationId, List<Long> seatIds) {
        jdbc.batchUpdate("INSERT INTO reservation_seats (reservation_id, seat_id) VALUES (?, ?)", seatIds, seatIds.size(),
                (ps, seatId) -> {
                    ps.setObject(1, reservationId);
                    ps.setLong(2, seatId);
                });
    }

    public void insertPayment(UUID paymentId, UUID reservationId, long amountPaise) {
        jdbc.update("INSERT INTO payments (id, reservation_id, amount_paise, status, provider_idem_key) "
                + "VALUES (?, ?, ?, 'CAPTURED', ?)", paymentId, reservationId, amountPaise, "res:" + reservationId);
    }

    public void storeIdempotentResponse(String userId, String key, int status, String body) {
        jdbc.update("UPDATE idempotency_keys SET response_status = ?, response_body = ?::jsonb WHERE user_id = ? AND idem_key = ?",
                status, body, userId, key);
    }

    // ---------------------------------------------------------------- reads / cancel transaction

    public Optional<ReservationRow> findReservation(UUID id) {
        return reservationQuery("SELECT * FROM reservations WHERE id = ?", id);
    }

    /** LOCKS. First lock taken by a cancellation. */
    public Optional<ReservationRow> lockReservation(UUID id) {
        return reservationQuery("SELECT * FROM reservations WHERE id = ? FOR UPDATE", id);
    }

    public List<String> seatLabelsOf(UUID reservationId) {
        return jdbc.queryForList("SELECT s.label FROM reservation_seats rs JOIN seats s ON s.id = rs.seat_id "
                + "WHERE rs.reservation_id = ? ORDER BY s.id", String.class, reservationId);
    }

    /** LOCKS. Same ascending-id order as the reserve path. */
    public List<Long> lockSeatsOwnedBy(UUID reservationId) {
        return jdbc.queryForList("SELECT id FROM seats WHERE reservation_id = ? ORDER BY id FOR UPDATE", Long.class, reservationId);
    }

    /** Only frees seats this reservation still owns; can never release a seat someone else holds. */
    public int releaseSeats(UUID reservationId) {
        return jdbc.update("UPDATE seats SET status = 'AVAILABLE', reservation_id = NULL, updated_at = now() "
                + "WHERE reservation_id = ? AND status = 'CONFIRMED'", reservationId);
    }

    public void deactivateReservationSeats(UUID reservationId) {
        jdbc.update("UPDATE reservation_seats SET active = false WHERE reservation_id = ? AND active", reservationId);
    }

    public int decrementQuota(UUID showId, String userId, int seatCount) {
        return jdbc.update("UPDATE user_show_quota SET seats_reserved = seats_reserved - ? WHERE show_id = ? AND user_id = ?",
                seatCount, showId, userId);
    }

    public void refundPayment(UUID reservationId) {
        jdbc.update("UPDATE payments SET status = 'REFUNDED', refunded_at = now() WHERE reservation_id = ? AND status = 'CAPTURED'",
                reservationId);
    }

    public void markCancelled(UUID reservationId, Instant at) {
        jdbc.update("UPDATE reservations SET status = 'CANCELLED', cancelled_at = ? WHERE id = ? AND status = 'CONFIRMED'",
                Timestamp.from(at), reservationId);
    }

    // ---------------------------------------------------------------- helpers

    private List<SeatState> seatQuery(String sql, UUID showId, Collection<String> labels) {
        return jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(sql);
            ps.setObject(1, showId);
            ps.setArray(2, textArray(con, labels));
            return ps;
        }, (rs, i) -> new SeatState(rs.getLong("id"), rs.getString("label"), rs.getString("status"),
                rs.getObject("reservation_id", UUID.class)));
    }

    private Optional<ReservationRow> reservationQuery(String sql, UUID id) {
        return jdbc.query(sql, (rs, i) -> {
            Timestamp cancelled = rs.getTimestamp("cancelled_at");
            return new ReservationRow(rs.getObject("id", UUID.class), rs.getObject("show_id", UUID.class),
                    rs.getString("user_id"), rs.getString("status"), rs.getInt("seat_count"), rs.getLong("amount_paise"),
                    rs.getTimestamp("created_at").toInstant(), cancelled == null ? null : cancelled.toInstant());
        }, id).stream().findFirst();
    }

    private static Array textArray(Connection con, Collection<String> values) throws SQLException {
        return con.createArrayOf("text", values.toArray());
    }
}
