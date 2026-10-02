package dev.seatres.error;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import dev.seatres.web.RequestContext;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException e, HttpServletRequest req) {
        return respond(req, e.code(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e, HttpServletRequest req) {
        var fe = e.getBindingResult().getFieldError();
        String msg = fe != null ? fe.getField() + ": " + fe.getDefaultMessage() : "invalid request";
        return respond(req, ErrorCode.VALIDATION_FAILED, msg);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<ErrorResponse> handleMalformed(Exception e, HttpServletRequest req) {
        return respond(req, ErrorCode.VALIDATION_FAILED, "malformed request");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException e, HttpServletRequest req) {
        return respond(req, ErrorCode.NOT_FOUND, "no such endpoint");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethod(HttpRequestMethodNotSupportedException e, HttpServletRequest req) {
        return respond(req, ErrorCode.METHOD_NOT_ALLOWED, "method not allowed");
    }

    /**
     * Infrastructure failures. Classified by SQLSTATE so that DB trouble is reported as 503
     * (retryable, and with an idempotency key retrying is always safe) rather than a bare 500.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e, HttpServletRequest req) {
        ErrorCode code = classify(e);
        if (code == ErrorCode.INTERNAL_ERROR) {
            log.error("unexpected error", e);
        } else {
            log.warn("infrastructure error classified as {}: {}", code, rootMessage(e));
        }
        String message = switch (code) {
            case DB_UNAVAILABLE -> "database unavailable, retry later";
            case CONTENTION_TIMEOUT -> "timed out under contention, retry with the same idempotency key";
            default -> "internal error";
        };
        return respond(req, code, message);
    }

    public static ErrorCode classify(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof CannotGetJdbcConnectionException || t instanceof SQLTransientConnectionException
                    || t instanceof DataAccessResourceFailureException) {
                return ErrorCode.DB_UNAVAILABLE;
            }
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                String state = sql.getSQLState();
                // 08xxx connection exception, 57P01/57P03 admin shutdown / cannot connect now
                if (state.startsWith("08") || state.equals("57P01") || state.equals("57P03")) {
                    return ErrorCode.DB_UNAVAILABLE;
                }
                // deadlock, serialization failure, lock_timeout, statement_timeout
                if (state.equals("40P01") || state.equals("40001") || state.equals("55P03")
                        || state.equals("57014")) {
                    return ErrorCode.CONTENTION_TIMEOUT;
                }
            }
        }
        return ErrorCode.INTERNAL_ERROR;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    private static ResponseEntity<ErrorResponse> respond(HttpServletRequest req, ErrorCode code, String message) {
        RequestContext.setErrorCode(req, code.name());
        var builder = ResponseEntity.status(code.status());
        if (code.status().is5xxServerError() && code != ErrorCode.INTERNAL_ERROR) {
            builder.header("Retry-After", "1");
        }
        return builder.body(new ErrorResponse(code.name(), message, MDC.get(RequestContext.MDC_REQUEST_ID)));
    }
}
