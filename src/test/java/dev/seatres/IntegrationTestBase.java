package dev.seatres;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real end-to-end harness: real PostgreSQL (Testcontainers), real HTTP over a socket, real
 * connection pool. Concurrency tests release all requests through a start gate so they hit the
 * server at the same instant.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestBase {

    /** One container for the whole test run (singleton pattern). */
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withCommand("postgres", "-c", "max_connections=200");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.datasource.hikari.maximum-pool-size", () -> "30");
        r.add("seatres.db.lock-timeout-ms", () -> "1000");
    }

    public record Resp(int status, JsonNode body) {
        public String code() {
            return body.path("code").asText(null);
        }

        public String reservationId() {
            return body.path("reservation_id").asText(null);
        }
    }

    protected static final ObjectMapper JSON = new ObjectMapper();
    protected static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .executor(Executors.newFixedThreadPool(64))
            .build();

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbc;

    private final List<String> showsCreated = new ArrayList<>();

    // ------------------------------------------------------------------ API helpers

    protected Resp call(String method, String path, String token, Object body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json");
            if (token != null) {
                b.header("Authorization", "Bearer " + token);
            }
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            HttpResponse<String> r = sendRetryingConnect(b.build());
            JsonNode node = r.body() == null || r.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(r.body());
            return new Resp(r.statusCode(), node);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * A ConnectException means the TCP connection was never established, so the server never saw
     * the request and retrying is safe. (Windows refuses some of several hundred simultaneous
     * loopback connects; this is a client-side artefact, not a server response.)
     */
    private static HttpResponse<String> sendRetryingConnect(HttpRequest req) throws Exception {
        for (int attempt = 1; ; attempt++) {
            try {
                return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            } catch (java.net.ConnectException e) {
                if (attempt >= 20) {
                    throw e;
                }
                Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextLong(50, 150) * attempt);
            }
        }
    }

    protected String token(String userId) {
        return call("POST", "/auth/dev-token", null, Map.of("user_id", userId)).body().get("token").asText();
    }

    protected String adminToken() {
        return call("POST", "/auth/dev-token", null, Map.of("user_id", "admin", "role", "admin")).body().get("token").asText();
    }

    protected String createShow(List<String> seats, Integer perUserLimit) {
        var body = new java.util.HashMap<String, Object>();
        body.put("name", "test-show");
        body.put("seats", seats);
        body.put("price_paise", 25000);
        if (perUserLimit != null) {
            body.put("per_user_limit", perUserLimit);
        }
        Resp r = call("POST", "/shows", adminToken(), body);
        assertThat(r.status()).isEqualTo(201);
        String id = r.body().get("show_id").asText();
        showsCreated.add(id);
        return id;
    }

    protected static List<String> seatRange(String row, int count) {
        List<String> seats = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            seats.add(row + i);
        }
        return seats;
    }

    protected Resp reserve(String token, String showId, List<String> seats, String key) {
        return call("POST", "/shows/" + showId + "/reserve", token, Map.of("seats", seats, "idempotency_key", key));
    }

    protected static String uniqueUser(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ------------------------------------------------------------------ concurrency harness

    /** Runs n tasks so that they all start at the same instant; returns results in task order. */
    protected static <T> List<T> concurrently(int n, IntFunction<Callable<T>> task) {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Callable<T> c = task.apply(i);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return c.call();
                }));
            }
            ready.await(30, TimeUnit.SECONDS);
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(120, TimeUnit.SECONDS));
            }
            return results;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            pool.shutdownNow();
        }
    }

    protected static long count(List<Resp> rs, int status) {
        return rs.stream().filter(r -> r.status() == status).count();
    }

    protected static long count(List<Resp> rs, int status, String code) {
        return rs.stream().filter(r -> r.status() == status && code.equals(r.code())).count();
    }

    protected static void assertNo5xx(List<Resp> rs) {
        assertThat(rs).noneMatch(r -> r.status() >= 500);
    }

    // ------------------------------------------------------------------ invariants after every test

    @AfterEach
    void reconciliationHoldsAfterEveryTest() {
        for (String showId : showsCreated) {
            JsonNode s = call("GET", "/shows/" + showId, null, null).body();
            assertThat(s.get("available").asInt() + s.get("held").asInt() + s.get("confirmed").asInt())
                    .as("available + held + confirmed == total for show %s", showId)
                    .isEqualTo(s.get("total_seats").asInt());
        }
        JsonNode rec = call("GET", "/admin/reconciliation", adminToken(), null).body();
        assertThat(rec.get("ok").asBoolean()).as("reconciliation checks: %s", rec).isTrue();
    }
}
