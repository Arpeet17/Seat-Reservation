package dev.seatres;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyIT extends IntegrationTestBase {

    @org.springframework.beans.factory.annotation.Autowired
    io.micrometer.core.instrument.MeterRegistry meters;

    // ------------------------------------------------------------------ Test 1
    @Test
    void singleSeatRace_exactlyOneWinner() {
        String show = createShow(seatRange("A", 20), null);
        int n = 500;
        List<String> tokens = IntStream.range(0, n).mapToObj(i -> token(uniqueUser("racer"))).toList();

        List<Resp> rs = concurrently(n, i -> () -> reserve(tokens.get(i), show, List.of("A12"), "k-" + i));

        assertNo5xx(rs);
        assertThat(count(rs, 201)).isEqualTo(1);
        assertThat(count(rs, 409, "SEAT_UNAVAILABLE")).isEqualTo(n - 1);
        Integer owners = jdbc.queryForObject(
                "SELECT count(*) FROM reservation_seats rs JOIN seats s ON s.id = rs.seat_id "
                        + "WHERE s.show_id = ?::uuid AND s.label = 'A12' AND rs.active", Integer.class, show);
        assertThat(owners).isEqualTo(1);
    }

    // ------------------------------------------------------------------ Test 2
    @Test
    void sameIdempotencyKeyConcurrently_executesOnce() {
        String show = createShow(seatRange("A", 20), null);
        String user = uniqueUser("dup");
        String tok = token(user);

        List<Resp> rs = concurrently(100, i -> () -> reserve(tok, show, List.of("A12"), "abc"));

        assertNo5xx(rs);
        assertThat(rs).allMatch(r -> r.status() == 201);
        assertThat(rs.stream().map(Resp::reservationId).distinct()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservations WHERE user_id = ?", Integer.class, user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments p JOIN reservations r ON r.id = p.reservation_id "
                + "WHERE r.user_id = ?", Integer.class, user)).as("no double charge").isEqualTo(1);
    }

    // ------------------------------------------------------------------ Test 3
    @Test
    void sameKeyDifferentBody_conflicts() {
        String show = createShow(seatRange("A", 20), null);
        String tok = token(uniqueUser("reuse"));

        assertThat(reserve(tok, show, List.of("A12"), "abc").status()).isEqualTo(201);
        Resp replay = reserve(tok, show, List.of("A12"), "abc");
        assertThat(replay.status()).isEqualTo(201);
        Resp conflict = reserve(tok, show, List.of("A13"), "abc");
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(call("GET", "/shows/" + show, null, null).body().get("confirmed").asInt()).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentBodiesConcurrently_onlyOneBodyExecutes() {
        String show = createShow(seatRange("A", 20), null);
        String user = uniqueUser("reuse-c");
        String tok = token(user);

        List<Resp> rs = concurrently(100, i -> () -> reserve(tok, show, List.of(i % 2 == 0 ? "A12" : "A13"), "same-key"));

        assertNo5xx(rs);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservations WHERE user_id = ?", Integer.class, user)).isEqualTo(1);
        assertThat(rs.stream().filter(r -> r.status() == 201).map(Resp::reservationId).distinct()).hasSize(1);
        assertThat(rs).allMatch(r -> r.status() == 201 || "IDEMPOTENCY_KEY_REUSED".equals(r.code()));
    }

    // ------------------------------------------------------------------ Test 4
    @Test
    void perUserLimitHoldsUnderConcurrency() {
        String show = createShow(seatRange("A", 40), 4);
        String user = uniqueUser("greedy");
        String tok = token(user);

        List<Resp> rs = concurrently(20, i -> () -> reserve(tok, show, List.of("A" + (i + 1)), "k-" + i));

        assertNo5xx(rs);
        assertThat(count(rs, 201)).isEqualTo(4);
        assertThat(count(rs, 409, "PER_USER_LIMIT_EXCEEDED")).isEqualTo(16);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seats s JOIN reservations r ON r.id = s.reservation_id "
                + "WHERE r.user_id = ?", Integer.class, user)).isEqualTo(4);
    }

    @Test
    void perUserLimitHoldsWithMixedMultiSeatRequests() {
        String show = createShow(seatRange("B", 60), 4);
        String user = uniqueUser("greedy-multi");
        String tok = token(user);

        // 15 concurrent requests of 1-3 seats each over disjoint seats.
        List<List<String>> requests = new ArrayList<>();
        int next = 1;
        for (int i = 0; i < 15; i++) {
            int size = 1 + i % 3;
            List<String> seats = new ArrayList<>();
            for (int j = 0; j < size; j++) {
                seats.add("B" + next++);
            }
            requests.add(seats);
        }
        List<Resp> rs = concurrently(requests.size(), i -> () -> reserve(tok, show, requests.get(i), "m-" + i));

        assertNo5xx(rs);
        int held = jdbc.queryForObject("SELECT count(*) FROM seats s JOIN reservations r ON r.id = s.reservation_id "
                + "WHERE r.user_id = ?", Integer.class, user);
        assertThat(held).isLessThanOrEqualTo(4).isPositive();
    }

    // ------------------------------------------------------------------ Test 5
    @Test
    void differentUsersDifferentSeats_allSucceed() {
        String show = createShow(seatRange("C", 100), null);
        List<String> tokens = IntStream.range(0, 100).mapToObj(i -> token(uniqueUser("u"))).toList();

        List<Resp> rs = concurrently(100, i -> () -> reserve(tokens.get(i), show, List.of("C" + (i + 1)), "k"));

        assertThat(rs).allMatch(r -> r.status() == 201);
        assertThat(call("GET", "/shows/" + show, null, null).body().get("available").asInt()).isZero();
    }

    // ------------------------------------------------------------------ Test 6
    @Test
    void multiSeatReservationIsAllOrNothing() {
        for (int round = 0; round < 30; round++) {
            String show = createShow(List.of("A12", "A13", "A14"), null);
            String x = token(uniqueUser("pair"));
            String y = token(uniqueUser("single"));

            List<Resp> rs = concurrently(2, i -> () -> i == 0
                    ? reserve(x, show, List.of("A13", "A12"), "pair")
                    : reserve(y, show, List.of("A13"), "single"));

            assertNo5xx(rs);
            Map<String, String> status = seatStatus(show);
            if (rs.get(0).status() == 201) {
                assertThat(rs.get(1).code()).isEqualTo("SEAT_UNAVAILABLE");
                assertThat(status).containsEntry("A12", "confirmed").containsEntry("A13", "confirmed");
            } else {
                assertThat(rs.get(0).code()).isEqualTo("SEAT_UNAVAILABLE");
                assertThat(rs.get(1).status()).isEqualTo(201);
                assertThat(status).as("loser must not partially reserve").containsEntry("A12", "available");
            }
        }
    }

    @Test
    void overlappingMultiSeatRequestsInReverseOrder_noDeadlocksNoDoubleSell() {
        String show = createShow(seatRange("D", 10), 10);
        Random rnd = new Random(42);
        List<List<String>> requests = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            List<String> seats = new ArrayList<>(new HashSet<>(List.of("D" + (1 + rnd.nextInt(10)), "D" + (1 + rnd.nextInt(10)),
                    "D" + (1 + rnd.nextInt(10)))));
            Collections.shuffle(seats, rnd);   // arbitrary request order; server must lock in sorted order
            requests.add(seats);
        }
        List<String> tokens = IntStream.range(0, 200).mapToObj(i -> token(uniqueUser("dl"))).toList();

        List<Resp> rs = concurrently(200, i -> () -> reserve(tokens.get(i), show, requests.get(i), "k"));

        assertNo5xx(rs);   // a deadlock would surface as 40P01 -> 503
        Set<String> sold = new HashSet<>();
        for (int i = 0; i < rs.size(); i++) {
            if (rs.get(i).status() == 201) {
                for (String seat : requests.get(i)) {
                    assertThat(sold.add(seat)).as("seat %s sold twice", seat).isTrue();
                }
            }
        }
        assertThat(seatStatus(show).values().stream().filter("confirmed"::equals).count()).isEqualTo(sold.size());
    }

    // ------------------------------------------------------------------ Test 7
    @Test
    void cancellationRacingReservations_neverTwoOwners() {
        for (int round = 0; round < 10; round++) {
            String show = createShow(List.of("A12"), null);
            String owner = token(uniqueUser("owner"));
            Resp first = reserve(owner, show, List.of("A12"), "orig");
            assertThat(first.status()).isEqualTo(201);
            String rid = first.reservationId();
            List<String> others = IntStream.range(0, 50).mapToObj(i -> token(uniqueUser("snipe"))).toList();

            List<Resp> rs = concurrently(51, i -> () -> i == 0
                    ? call("POST", "/reservations/" + rid + "/cancel", owner, null)
                    : reserve(others.get(i - 1), show, List.of("A12"), "s"));

            assertNo5xx(rs);
            assertThat(rs.get(0).status()).isEqualTo(200);
            assertThat(rs.subList(1, rs.size()).stream().filter(r -> r.status() == 201).count()).isLessThanOrEqualTo(1);
            Integer activeOwners = jdbc.queryForObject("SELECT count(*) FROM reservation_seats rs JOIN seats s ON s.id = rs.seat_id "
                    + "WHERE s.show_id = ?::uuid AND rs.active", Integer.class, show);
            assertThat(activeOwners).isLessThanOrEqualTo(1);
        }
    }

    @Test
    void concurrentCancelsOfSameReservation_releaseOnce() {
        String show = createShow(seatRange("E", 4), null);
        String user = uniqueUser("canceller");
        String tok = token(user);
        String rid = reserve(tok, show, List.of("E1", "E2"), "k").reservationId();

        List<Resp> rs = concurrently(20, i -> () -> call("POST", "/reservations/" + rid + "/cancel", tok, null));

        assertThat(rs).allMatch(r -> r.status() == 200 && "cancelled".equals(r.body().get("status").asText()));
        assertThat(jdbc.queryForObject("SELECT seats_reserved FROM user_show_quota WHERE user_id = ?", Integer.class, user)).isZero();
    }

    @Test
    void cannotCancelSomeoneElsesReservation() {
        String show = createShow(seatRange("F", 2), null);
        String rid = reserve(token(uniqueUser("victim")), show, List.of("F1"), "k").reservationId();

        Resp r = call("POST", "/reservations/" + rid + "/cancel", token(uniqueUser("attacker")), null);

        assertThat(r.status()).isEqualTo(404);
        assertThat(seatStatus(show)).containsEntry("F1", "confirmed");
    }

    // ------------------------------------------------------------------ Test 9
    @Test
    void identityComesFromTokenNotBody() {
        String show = createShow(seatRange("G", 2), null);
        String attacker = uniqueUser("attacker");
        Map<String, Object> body = new HashMap<>();
        body.put("user_id", "victim");
        body.put("seats", List.of("G1"));
        body.put("idempotency_key", "spoof");

        Resp r = call("POST", "/shows/" + show + "/reserve", token(attacker), body);

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.body().get("user_id").asText()).isEqualTo(attacker);
        assertThat(jdbc.queryForObject("SELECT user_id FROM reservations WHERE id = ?::uuid", String.class, r.reservationId()))
                .isEqualTo(attacker);
    }

    @Test
    void missingOrForgedTokenIsRejected() {
        String show = createShow(seatRange("H", 2), null);
        assertThat(reserve(null, show, List.of("H1"), "k").status()).isEqualTo(401);
        assertThat(reserve("user-123", show, List.of("H1"), "k").status()).isEqualTo(401);
        String good = token("someone");
        String forged = good.substring(0, good.lastIndexOf('.') + 1) + "AAAA";
        assertThat(reserve(forged, show, List.of("H1"), "k").status()).isEqualTo(401);
        assertThat(call("POST", "/shows", token("not-admin"), Map.of("name", "x", "seats", List.of("A"), "price_paise", 1)).status())
                .isEqualTo(403);
    }

    @Test
    void unknownShowAndSeatAreClientErrors() {
        String show = createShow(seatRange("J", 2), null);
        String tok = token(uniqueUser("u"));
        assertThat(reserve(tok, UUID.randomUUID().toString(), List.of("J1"), "k").status()).isEqualTo(404);
        assertThat(reserve(tok, show, List.of("Z99"), "k").code()).isEqualTo("UNKNOWN_SEAT");
        assertThat(reserve(tok, show, List.of("J1", "J1"), "k").code()).isEqualTo("DUPLICATE_SEAT");
        assertThat(reserve(tok, show, List.of(), "k").status()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ transient lock failures
    @Test
    void lockTimeoutIsRetriedInternallyNotSurfacedAs5xx() throws Exception {
        String show = createShow(seatRange("K", 2), null);
        String tok = token(uniqueUser("patient"));

        // An external transaction holds K1's row lock for longer than lock_timeout (2s in tests).
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword())) {
            c.setAutoCommit(false);
            try (var ps = c.prepareStatement("SELECT 1 FROM seats WHERE show_id = ?::uuid AND label = 'K1' FOR UPDATE")) {
                ps.setString(1, show);
                ps.executeQuery();
            }
            var pending = java.util.concurrent.CompletableFuture.supplyAsync(() -> reserve(tok, show, List.of("K1"), "k"));
            Thread.sleep(2500);   // first attempt times out at 2s; the retry is waiting when we release
            c.rollback();
            Resp r = pending.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(r.status()).isEqualTo(201);
        }
        assertThat(meters.find("db.transaction.retries").tag("type", "lock_timeout").counter())
                .isNotNull().satisfies(c -> assertThat(c.count()).isPositive());
    }

    // ------------------------------------------------------------------ logs access
    @Test
    void recentLogsAreQueryableByRequestId() throws Exception {
        String rid = "trace-" + UUID.randomUUID();
        HTTP.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                        "http://localhost:" + port + "/shows/" + UUID.randomUUID())).header("X-Request-Id", rid).build(),
                java.net.http.HttpResponse.BodyHandlers.discarding());

        String admin = adminToken();
        String body = "";
        for (int i = 0; i < 50 && !body.contains(rid); i++) {   // async appender: allow a moment
            Thread.sleep(100);
            body = HTTP.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                            "http://localhost:" + port + "/admin/logs?request_id=" + rid))
                    .header("Authorization", "Bearer " + admin).build(), java.net.http.HttpResponse.BodyHandlers.ofString()).body();
        }
        assertThat(body).contains(rid).contains("\"status\":404").contains("SHOW_NOT_FOUND").doesNotContain("Bearer");
        assertThat(call("GET", "/admin/logs", token(uniqueUser("nosy")), null).status()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, String> seatStatus(String showId) {
        Map<String, String> m = new HashMap<>();
        call("GET", "/shows/" + showId, null, null).body().get("seats")
                .forEach(s -> m.put(s.get("label").asText(), s.get("status").asText()));
        return m;
    }
}
