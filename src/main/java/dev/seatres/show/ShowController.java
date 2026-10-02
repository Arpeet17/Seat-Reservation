package dev.seatres.show;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import dev.seatres.auth.Principal;
import dev.seatres.show.ShowService.ShowState;
import dev.seatres.web.RequestContext;

@RestController
public class ShowController {

    public record CreateShowRequest(
            @NotBlank @Size(max = 200) String name,
            @NotEmpty List<@NotNull @Pattern(regexp = "[A-Za-z0-9-]{1,16}") String> seats,
            @NotNull @PositiveOrZero @Max(100_000_000L) Long pricePaise,
            @Positive @Max(100) Integer perUserLimit) {
    }

    public record SeatView(String label, String status) {
    }

    public record ShowView(UUID showId, String name, long pricePaise, int perUserLimit, int totalSeats,
                           int available, int held, int confirmed, Instant createdAt, List<SeatView> seats) {

        static ShowView of(ShowState s) {
            return new ShowView(s.show().id(), s.show().name(), s.show().pricePaise(), s.show().perUserLimit(),
                    s.show().totalSeats(), s.available(), s.held(), s.confirmed(), s.show().createdAt(),
                    s.seats().stream().map(r -> new SeatView(r.label(), r.status().toLowerCase(Locale.ROOT))).toList());
        }
    }

    private final ShowService shows;

    public ShowController(ShowService shows) {
        this.shows = shows;
    }

    @PostMapping("/shows")
    @ResponseStatus(HttpStatus.CREATED)
    public ShowView create(Principal principal, @Valid @RequestBody CreateShowRequest req) {
        principal.requireAdmin();
        ShowState state = shows.create(req.name(), req.seats(), req.pricePaise(), req.perUserLimit());
        RequestContext.showId(state.show().id());
        return ShowView.of(state);
    }

    @GetMapping("/shows/{showId}")
    public ShowView get(@PathVariable UUID showId) {
        RequestContext.showId(showId);
        return ShowView.of(shows.state(showId));
    }
}
