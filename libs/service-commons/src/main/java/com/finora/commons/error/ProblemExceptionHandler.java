package com.finora.commons.error;

import com.finora.commons.correlation.CorrelationId;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Turns every MVC failure into a safe problem document with the request id. */
@RestControllerAdvice
public class ProblemExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ProblemExceptionHandler.class);

    private final ProblemWriter problems;

    public ProblemExceptionHandler(ProblemWriter problems) {
        this.problems = problems;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> handleApi(ApiException ex) {
        return respond(ex.code(), ex.getMessage(), ex.retryAfterSeconds());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, HttpServletRequest request) {
        // The cause is logged with the request id; the caller only ever sees a generic message.
        LOG.error("Unhandled exception for request {} {}", request.getMethod(), request.getRequestURI(), ex);
        return respond(ErrorCode.INTERNAL, null, null);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        return respond(ErrorCode.forStatus(statusCode.value()), null, null);
    }

    private ResponseEntity<Object> respond(ErrorCode code, String detail, Integer retryAfterSeconds) {
        Map<String, Object> body = problems.body(code, detail, CorrelationId.current() != null
                ? CorrelationId.current() : CorrelationId.generate(), retryAfterSeconds);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(code.status())
                .contentType(MediaType.parseMediaType(ProblemWriter.CONTENT_TYPE));
        if (retryAfterSeconds != null) {
            builder.header("Retry-After", String.valueOf(retryAfterSeconds));
        }
        return builder.body(body);
    }
}
