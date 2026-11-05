package com.corridoriq.backend.prediction;

import org.springframework.http.HttpStatusCode;

/**
 * A failed call to the prediction service, with the status and JSON body the backend returns for it:
 * the service's own 4xx answer, 502 for its 5xx errors, or 503 when it cannot be reached.
 */
public class PredictionServiceException extends RuntimeException {

    private final HttpStatusCode status;
    private final String body;

    public PredictionServiceException(HttpStatusCode status, String body, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.body = body;
    }

    public HttpStatusCode status() {
        return status;
    }

    /** JSON with a "detail" field, like every other error the API returns. */
    public String body() {
        return body;
    }
}
