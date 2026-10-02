package dev.seatres.show;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.seatres.error.ApiException;
import dev.seatres.error.ErrorCode;
import dev.seatres.show.ShowRepository.SeatRow;
import dev.seatres.show.ShowRepository.ShowRow;

@Service
public class ShowService {

    public static final int DEFAULT_PER_USER_LIMIT = 4;

    public record ShowState(ShowRow show, List<SeatRow> seats, int available, int held, int confirmed) {
    }

    private final ShowRepository repo;
    private final int maxSeatsPerShow;
    /** Shows are immutable once created, so caching their metadata can never serve stale data. */
    private final ConcurrentHashMap<UUID, ShowRow> cache = new ConcurrentHashMap<>();

    public ShowService(ShowRepository repo, @Value("${seatres.limits.max-seats-per-show}") int maxSeatsPerShow) {
        this.repo = repo;
        this.maxSeatsPerShow = maxSeatsPerShow;
    }

    @Transactional
    public ShowState create(String name, List<String> seatLabels, long pricePaise, Integer perUserLimit) {
        if (seatLabels.size() > maxSeatsPerShow) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "a show may have at most " + maxSeatsPerShow + " seats");
        }
        Set<String> unique = new HashSet<>();
        for (String label : seatLabels) {
            if (!unique.add(label)) {
                throw new ApiException(ErrorCode.DUPLICATE_SEAT, "duplicate seat label: " + label);
            }
        }
        int limit = perUserLimit == null ? DEFAULT_PER_USER_LIMIT : perUserLimit;
        ShowRow show = new ShowRow(UUID.randomUUID(), name.trim(), pricePaise, limit, seatLabels.size(),
                Instant.now().truncatedTo(ChronoUnit.MICROS));
        repo.insertShow(show);
        repo.insertSeats(show.id(), seatLabels);
        List<SeatRow> seats = seatLabels.stream().map(l -> new SeatRow(l, "AVAILABLE")).toList();
        return new ShowState(show, seats, seats.size(), 0, 0);
    }

    public ShowRow require(UUID showId) {
        ShowRow cached = cache.get(showId);
        if (cached != null) {
            return cached;
        }
        Optional<ShowRow> found = repo.findShow(showId);
        if (found.isEmpty()) {
            throw new ApiException(ErrorCode.SHOW_NOT_FOUND, "show not found");
        }
        cache.put(showId, found.get());
        return found.get();
    }

    public ShowState state(UUID showId) {
        ShowRow show = require(showId);
        List<SeatRow> seats = repo.findSeats(showId);
        int available = 0, held = 0, confirmed = 0;
        for (SeatRow s : seats) {
            switch (s.status()) {
                case "AVAILABLE" -> available++;
                case "HELD" -> held++;
                case "CONFIRMED" -> confirmed++;
                default -> throw new IllegalStateException("unknown seat status " + s.status());
            }
        }
        return new ShowState(show, seats, available, held, confirmed);
    }
}
