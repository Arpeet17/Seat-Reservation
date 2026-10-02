package dev.seatres.error;

import org.springframework.http.HttpStatus;

/**
 * Every outcome the API can return other than success. Expected domain conflicts are 4xx; 5xx is
 * reserved for infrastructure trouble (database unavailable, outcome unknown) and genuine bugs.
 */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    UNKNOWN_SEAT(HttpStatus.BAD_REQUEST),
    DUPLICATE_SEAT(HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    SHOW_NOT_FOUND(HttpStatus.NOT_FOUND),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
    SEAT_UNAVAILABLE(HttpStatus.CONFLICT),
    PER_USER_LIMIT_EXCEEDED(HttpStatus.CONFLICT),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT),
    DB_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    CONTENTION_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE),
    OUTCOME_UNKNOWN(HttpStatus.SERVICE_UNAVAILABLE),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
