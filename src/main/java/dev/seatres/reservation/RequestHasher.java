package dev.seatres.reservation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.UUID;

/**
 * Canonical request fingerprint for idempotency.
 *
 * <pre>v1|&lt;show_id&gt;|&lt;seat labels, sorted, comma-joined&gt;</pre>
 *
 * Seats are a set: ["A13","A12"] and ["A12","A13"] hash identically because the reservation they
 * produce is identical. The user id is not part of the hash because it is already part of the
 * idempotency table's primary key. The "v1" prefix allows the canonical form to evolve without
 * misclassifying old keys.
 */
public final class RequestHasher {

    private RequestHasher() {
    }

    public static byte[] hash(UUID showId, Collection<String> seatLabels) {
        String canonical = "v1|" + showId + "|" + String.join(",", seatLabels.stream().sorted().toList());
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean same(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }
}
