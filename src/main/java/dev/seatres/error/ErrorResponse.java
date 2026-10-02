package dev.seatres.error;

public record ErrorResponse(String code, String message, String requestId) {
}
