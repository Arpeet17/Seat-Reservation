package dev.seatres.web;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.MDC;

/** Request-scoped fields that end up in the single structured access-log line per request. */
public final class RequestContext {

    public static final String MDC_REQUEST_ID = "request_id";
    public static final String MDC_USER_ID = "user_id";
    public static final String MDC_SHOW_ID = "show_id";
    public static final String MDC_RESERVATION_ID = "reservation_id";

    static final String ATTR_ERROR_CODE = "seatres.error_code";

    private RequestContext() {
    }

    public static void setErrorCode(HttpServletRequest req, String code) {
        req.setAttribute(ATTR_ERROR_CODE, code);
    }

    public static void showId(Object showId) {
        MDC.put(MDC_SHOW_ID, String.valueOf(showId));
    }

    public static void reservationId(Object reservationId) {
        MDC.put(MDC_RESERVATION_ID, String.valueOf(reservationId));
    }
}
