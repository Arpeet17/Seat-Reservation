package dev.seatres.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "seatres.auth")
public record AuthProperties(String secret, boolean devTokenEndpoint) {

    public AuthProperties {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("seatres.auth.secret (AUTH_SECRET) must be at least 32 characters");
        }
    }
}
