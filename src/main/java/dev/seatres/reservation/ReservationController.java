package dev.seatres.reservation;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import dev.seatres.auth.Principal;
import dev.seatres.reservation.ReservationService.ReserveResult;
import dev.seatres.web.RequestContext;

@RestController
public class ReservationController {

    /**
     * Note what is absent: there is no user_id field. Identity comes only from the verified bearer
     * token; a "user_id" in the body is an unknown property and is ignored by Jackson.
     */
    public record ReserveRequest(
            @NotEmpty List<@NotNull @Pattern(regexp = "[A-Za-z0-9-]{1,16}") String> seats,
            @NotNull @Pattern(regexp = "[\\x21-\\x7E]{1,128}", message = "must be 1-128 printable ASCII characters")
            String idempotencyKey) {
    }

    private final ReservationService reservations;

    public ReservationController(ReservationService reservations) {
        this.reservations = reservations;
    }

    @PostMapping("/shows/{showId}/reserve")
    public ResponseEntity<ReservationView> reserve(Principal principal, @PathVariable UUID showId,
                                                   @Valid @RequestBody ReserveRequest req) {
        RequestContext.showId(showId);
        ReserveResult result = reservations.reserve(principal, showId, req.seats(), req.idempotencyKey());
        RequestContext.reservationId(result.reservation().reservationId());
        var builder = ResponseEntity.status(result.status());
        if (result.replayed()) {
            builder.header("Idempotent-Replayed", "true");
        }
        return builder.body(result.reservation());
    }

    @PostMapping("/reservations/{reservationId}/cancel")
    public ReservationView cancel(Principal principal, @PathVariable UUID reservationId) {
        RequestContext.reservationId(reservationId);
        return reservations.cancel(principal, reservationId);
    }

    @GetMapping("/reservations/{reservationId}")
    public ReservationView get(Principal principal, @PathVariable UUID reservationId) {
        RequestContext.reservationId(reservationId);
        return reservations.get(principal, reservationId);
    }
}
