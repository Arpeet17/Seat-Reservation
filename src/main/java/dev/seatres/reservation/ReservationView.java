package dev.seatres.reservation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReservationView(UUID reservationId, UUID showId, String userId, List<String> seats, long amountPaise,
                              String status, Instant createdAt, Instant cancelledAt) {
}
