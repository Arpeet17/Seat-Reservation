package dev.seatres.show;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ShowRepository {

    public record ShowRow(UUID id, String name, long pricePaise, int perUserLimit, int totalSeats, Instant createdAt) {
    }

    public record SeatRow(String label, String status) {
    }

    private final JdbcTemplate jdbc;

    public ShowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertShow(ShowRow s) {
        jdbc.update("INSERT INTO shows (id, name, price_paise, per_user_limit, total_seats, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                s.id(), s.name(), s.pricePaise(), s.perUserLimit(), s.totalSeats(), Timestamp.from(s.createdAt()));
    }

    public void insertSeats(UUID showId, List<String> labels) {
        jdbc.batchUpdate("INSERT INTO seats (show_id, label) VALUES (?, ?)", labels, 500, (ps, label) -> {
            ps.setObject(1, showId);
            ps.setString(2, label);
        });
    }

    public Optional<ShowRow> findShow(UUID id) {
        return jdbc.query("SELECT id, name, price_paise, per_user_limit, total_seats, created_at FROM shows WHERE id = ?",
                (rs, i) -> new ShowRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getLong("price_paise"),
                        rs.getInt("per_user_limit"), rs.getInt("total_seats"), rs.getTimestamp("created_at").toInstant()),
                id).stream().findFirst();
    }

    /** Single statement, therefore a single consistent snapshot of every seat's status. */
    public List<SeatRow> findSeats(UUID showId) {
        return jdbc.query("SELECT label, status FROM seats WHERE show_id = ? ORDER BY id",
                (rs, i) -> new SeatRow(rs.getString("label"), rs.getString("status")), showId);
    }
}
