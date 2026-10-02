package dev.seatres.auth;

import java.time.Duration;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import dev.seatres.error.ApiException;
import dev.seatres.error.ErrorCode;

/**
 * Demo-only token minting so reviewers can exercise the API with curl. Disabled with
 * AUTH_DEV_TOKEN_ENDPOINT=false; in a real system tokens come from an identity provider.
 */
@RestController
public class DevTokenController {

    public record DevTokenRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._@-]{1,64}") String userId,
            @Pattern(regexp = "user|admin") String role) {
    }

    private final TokenService tokens;
    private final AuthProperties props;

    public DevTokenController(TokenService tokens, AuthProperties props) {
        this.tokens = tokens;
        this.props = props;
    }

    @PostMapping("/auth/dev-token")
    public Map<String, Object> mint(@Valid @RequestBody DevTokenRequest req) {
        if (!props.devTokenEndpoint()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "no such endpoint");
        }
        Principal.Role role = "admin".equals(req.role()) ? Principal.Role.ADMIN : Principal.Role.USER;
        String token = tokens.issue(req.userId(), role, Duration.ofHours(24));
        return Map.of("token", token, "user_id", req.userId(), "role", role.name().toLowerCase(), "expires_in", 86400);
    }
}
