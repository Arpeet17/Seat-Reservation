package dev.seatres.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

/**
 * Minimal HS256 JWT issue/verify. Hand-rolled (about 80 lines) to avoid pulling in a JWT library
 * for one algorithm; the only accepted alg is HS256, so "alg: none" and algorithm-confusion attacks
 * are impossible by construction.
 */
@Component
public class TokenService {

    public static final Pattern USER_ID = Pattern.compile("[A-Za-z0-9._@-]{1,64}");

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final String HEADER = B64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private final byte[] key;
    private final ObjectMapper mapper = new ObjectMapper();

    public TokenService(AuthProperties props) {
        this.key = props.secret().getBytes(StandardCharsets.UTF_8);
    }

    public String issue(String userId, Principal.Role role, Duration ttl) {
        if (!USER_ID.matcher(userId).matches()) {
            throw new IllegalArgumentException("invalid user id");
        }
        Instant now = Instant.now();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", userId);
        claims.put("role", role.name().toLowerCase());
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plus(ttl).getEpochSecond());
        try {
            String payload = B64.encodeToString(mapper.writeValueAsBytes(claims));
            String signingInput = HEADER + "." + payload;
            return signingInput + "." + B64.encodeToString(hmac(signingInput));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<Principal> verify(String token) {
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) {
                return Optional.empty();
            }
            JsonNode header = mapper.readTree(B64D.decode(parts[0]));
            if (!"HS256".equals(header.path("alg").asText())) {
                return Optional.empty();
            }
            byte[] expected = hmac(parts[0] + "." + parts[1]);
            if (!MessageDigest.isEqual(expected, B64D.decode(parts[2]))) {
                return Optional.empty();
            }
            JsonNode claims = mapper.readTree(B64D.decode(parts[1]));
            long exp = claims.path("exp").asLong(0);
            if (exp <= Instant.now().getEpochSecond()) {
                return Optional.empty();
            }
            String sub = claims.path("sub").asText("");
            if (!USER_ID.matcher(sub).matches()) {
                return Optional.empty();
            }
            Principal.Role role = "admin".equals(claims.path("role").asText()) ? Principal.Role.ADMIN : Principal.Role.USER;
            return Optional.of(new Principal(sub, role));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private byte[] hmac(String input) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
    }
}
