package dev.seatres.auth;

import dev.seatres.error.ApiException;
import dev.seatres.error.ErrorCode;

/** The authenticated caller. Built only from a verified token, never from a request body. */
public record Principal(String userId, Role role) {

    public enum Role { USER, ADMIN }

    public void requireAdmin() {
        if (role != Role.ADMIN) {
            throw new ApiException(ErrorCode.FORBIDDEN, "admin role required");
        }
    }
}
