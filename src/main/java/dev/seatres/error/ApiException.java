package dev.seatres.error;

public class ApiException extends RuntimeException {

    private final ErrorCode code;

    public ApiException(ErrorCode code, String message) {
        super(message, null, false, false); // domain outcomes: no stack trace cost under contention
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
