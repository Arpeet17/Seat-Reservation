package dev.seatres;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;

import dev.seatres.api.HealthController;
import dev.seatres.auth.AuthProperties;
import dev.seatres.auth.Principal;
import dev.seatres.auth.TokenService;
import dev.seatres.reservation.RequestHasher;

import static org.assertj.core.api.Assertions.assertThat;

class UnitTests {

    private final TokenService tokens = new TokenService(new AuthProperties("x".repeat(40), true));

    @Test
    void requestHashIgnoresSeatOrderButNotSeatSetOrShow() {
        UUID show = UUID.randomUUID();
        byte[] a = RequestHasher.hash(show, List.of("A13", "A12"));
        assertThat(RequestHasher.same(a, RequestHasher.hash(show, List.of("A12", "A13")))).isTrue();
        assertThat(RequestHasher.same(a, RequestHasher.hash(show, List.of("A12")))).isFalse();
        assertThat(RequestHasher.same(a, RequestHasher.hash(UUID.randomUUID(), List.of("A12", "A13")))).isFalse();
        assertThat(a).hasSize(32);
    }

    @Test
    void tokenRoundTrip() {
        String t = tokens.issue("alice", Principal.Role.USER, Duration.ofMinutes(5));
        assertThat(tokens.verify(t)).contains(new Principal("alice", Principal.Role.USER));
    }

    @Test
    void tamperedExpiredOrUnsignedTokensAreRejected() {
        String t = tokens.issue("alice", Principal.Role.USER, Duration.ofMinutes(5));
        String[] p = t.split("\\.");
        String evilPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"bob\",\"role\":\"admin\",\"exp\":9999999999}".getBytes());
        assertThat(tokens.verify(p[0] + "." + evilPayload + "." + p[2])).isEmpty();

        String none = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes());
        assertThat(tokens.verify(none + "." + evilPayload + ".")).isEmpty();

        assertThat(tokens.verify(tokens.issue("alice", Principal.Role.USER, Duration.ofSeconds(-1)))).isEmpty();
        assertThat(tokens.verify("user-123")).isEmpty();

        TokenService otherKey = new TokenService(new AuthProperties("y".repeat(40), true));
        assertThat(tokens.verify(otherKey.issue("alice", Principal.Role.ADMIN, Duration.ofMinutes(5)))).isEmpty();
    }

    @Test
    void readinessFailsWhenDatabaseIsUnreachable() {
        DataSourceProperties props = new DataSourceProperties();
        props.setUrl("jdbc:postgresql://127.0.0.1:1/nothing");
        props.setUsername("u");
        props.setPassword("p");
        assertThat(new HealthController(props).ready().getStatusCode().value()).isEqualTo(503);
        assertThat(new HealthController(props).live()).containsEntry("status", "UP");
    }
}
